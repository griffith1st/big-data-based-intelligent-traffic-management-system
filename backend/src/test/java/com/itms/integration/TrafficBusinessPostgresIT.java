package com.itms.integration;

import com.itms.entity.*;
import com.itms.repository.*;
import com.itms.service.*;
import com.itms.service.impl.TrafficAlertServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Behavioral tests of business transactions, PostgreSQL queries and concurrent updates. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "itms.traffic.simulator.enabled=false", "spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false", "spring.jpa.hibernate.ddl-auto=update",
    "spring.datasource.username=postgres", "spring.datasource.password=",
    "spring.data.redis.database=1", "itms.demo.reset-default-users=false"
})
class TrafficBusinessPostgresIT {
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        int pg=Integer.parseInt(System.getProperty("test.postgres.port"));
        int redis=Integer.parseInt(System.getProperty("test.redis.port"));
        assertThat(pg).isBetween(1024,65535); assertThat(redis).isBetween(1024,65535);
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+pg+"/itms_concurrency_it");
        r.add("spring.data.redis.port",()->redis);
    }
    @Autowired TrafficDataService traffic;
    @Autowired TrafficDataRepository data;
    @Autowired TrafficSignalService signals;
    @Autowired TrafficSignalRepository signalRows;
    @Autowired TrafficAlertRepository alertRows;
    @Autowired TrafficPredictionService predictions;
    @Autowired TrafficPredictionRepository predictionRows;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @SpyBean TrafficAlertServiceImpl alerts;

    @BeforeEach void clean() {
        reset(alerts); alertRows.deleteAll(); signalRows.deleteAll(); predictionRows.deleteAll(); data.deleteAll();
        redis.delete(List.of("itms:traffic:realtime","itms:traffic:intersection-summary"));
    }
    TrafficData sample(String intersection,String direction,int count,LocalDateTime time) {
        return TrafficData.builder().intersectionId(intersection).direction(direction).vehicleCount(count)
            .avgSpeed(12.0).congestionLevel(3).recordTime(time).dataSource("TEST")
            .longitude(116.4).latitude(39.9).intersectionName(intersection).build();
    }
    TrafficData sample(String direction) { return sample("INT-001",direction,900,LocalDateTime.now()); }

    @Test void latestQueriesChooseExactlyOneRowPerDirectionWithIdTieBreak() {
        LocalDateTime now=LocalDateTime.now();
        data.save(sample("INT-001","NORTH",100,now));
        var winner=data.save(sample("INT-001","NORTH",200,now));
        var other=data.save(sample("INT-002","SOUTH",300,now.minusMinutes(1)));
        assertThat(data.findLatestTrafficData()).extracting(TrafficData::getId).containsExactly(winner.getId(),other.getId());
        assertThat(data.findRealTimeCongestion()).hasSize(2);
        assertThat(data.findCurrentCongestionNative()).hasSize(2);
    }
    @Test void historyLimitSelectsNewestRowsInDatabaseAndReturnsChronologicalOrder() {
        var now=LocalDateTime.now();
        for(int n=1;n<=6;n++) data.save(sample("INT-001","NORTH",n,now.minusSeconds(10-n)));
        assertThat(traffic.getHistoryData("INT-001",Integer.valueOf(2))).extracting(TrafficData::getVehicleCount).containsExactly(5,6);
    }
    @Test void standaloneTrafficSaveRollsBackWhenAlertFails() {
        doThrow(new IllegalStateException("alert write failed")).when(alerts).upsertCongestionAlert(any());
        assertThatThrownBy(()->traffic.saveTrafficData(sample("NORTH"))).hasMessage("alert write failed");
        assertThat(data.count()).isZero();
    }
    @Test void batchRollsBackAllRowsWhenLaterAlertFails() {
        doNothing().doThrow(new IllegalStateException("second alert failed")).when(alerts).upsertCongestionAlert(any());
        assertThatThrownBy(()->traffic.batchSave(List.of(sample("NORTH"),sample("SOUTH")))).hasMessage("second alert failed");
        assertThat(data.count()).isZero();
    }
    @Test void signalConcurrentInitializationCreatesOneRowPerDirection() throws Exception {
        concurrently(8,()->signals.getSignals("INT-001"));
        assertThat(signalRows.findByIntersectionId("INT-001")).hasSize(4);
    }
    @Test void signalOptimizationThenApplyIsRealStateTransitionAndPreservesCycle() {
        var before=signals.getSignals("INT-001");
        assertThat(before).allSatisfy(s->assertThat(s.getGreenDuration()).isEqualTo(40));
        data.save(sample("NORTH"));
        signals.optimizeSignals("INT-001","CONGESTION_FIRST");
        assertThat(signalRows.findByIntersectionId("INT-001")).allSatisfy(s->{
            assertThat(s.getGreenDuration()).isEqualTo(40); assertThat(s.getAppliedAt()).isNull();
        });
        signals.applySignalPlan("INT-001");
        assertThat(signalRows.findByIntersectionId("INT-001")).allSatisfy(s->{
            assertThat(s.getOptimizationStatus()).isEqualTo("APPLIED"); assertThat(s.getAppliedAt()).isNotNull();
            assertThat(s.getGreenDuration()+s.getYellowDuration()+s.getRedDuration()).isEqualTo(s.getCycleDuration());
        });
        assertThatThrownBy(()->signals.applySignalPlan("INT-001")).isInstanceOf(RuntimeException.class);
    }
    @Test void alertSeparatesDirectionsAndPredictionSourceAndRefreshesExisting() {
        alerts.upsertCongestionAlert(sample("NORTH")); alerts.upsertCongestionAlert(sample("SOUTH"));
        var predicted=sample("PREDICTED"); predicted.setDataSource("PREDICTION"); alerts.upsertCongestionAlert(predicted);
        alerts.upsertCongestionAlert(sample("NORTH"));
        assertThat(alertRows.count()).isEqualTo(3);
    }
    @Test void parallelSameConditionCreatesOneActiveAlert() throws Exception {
        concurrently(8,()->alerts.upsertCongestionAlert(sample("NORTH")));
        assertThat(alertRows.count()).isEqualTo(1);
    }
    @Test void resolvedAlertRemainsTerminalAndNewOccurrenceGetsNewRow() {
        alerts.upsertCongestionAlert(sample("NORTH")); var first=alertRows.findAll().get(0);
        alerts.resolveAlert(first.getId(),"operator-a");
        alerts.markAsRead(first.getId()); alerts.resolveAlert(first.getId(),"operator-b");
        assertThat(alertRows.findById(first.getId()).orElseThrow().getHandler()).isEqualTo("operator-a");
        alerts.upsertCongestionAlert(sample("NORTH"));
        assertThat(alertRows.count()).isEqualTo(2);
    }
    @Test void predictionAlertFailureRollsBackEntireBatch() {
        doThrow(new IllegalStateException("prediction alert failed")).when(alerts).upsertCongestionAlert(any());
        for(String direction:List.of("NORTH","SOUTH","EAST","WEST")) data.save(sample(direction));
        assertThatThrownBy(predictions::triggerPrediction).hasMessage("prediction alert failed");
        assertThat(predictionRows.count()).isZero();
    }
    @Test void recoveredConditionIgnoresLateOlderCongestionAndReadStaysReadOnRefresh() {
        var old=sample("NORTH"); old.setRecordTime(LocalDateTime.now().minusMinutes(2));
        alerts.upsertCongestionAlert(old); var id=alertRows.findAll().get(0).getId();
        alerts.markAsRead(id);
        var newer=sample("NORTH"); alerts.upsertCongestionAlert(newer);
        var refreshed=alertRows.findById(id).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo("READ");
        assertThat(refreshed.getLastSeenAt()).isEqualToIgnoringNanos(newer.getRecordTime());
        var recovered=sample("NORTH"); recovered.setCongestionLevel(0); alerts.upsertCongestionAlert(recovered);
        assertThat(alertRows.findById(id).orElseThrow().getStatus()).isEqualTo("RESOLVED");
        alerts.upsertCongestionAlert(old);
        assertThat(alertRows.countByStatus("UNREAD")).isZero();
    }

    @Test void lowRiskPredictionClosesPreviousPredictedAlertWithoutTouchingRealtime() {
        alerts.upsertCongestionAlert(sample("NORTH"));
        var predicted=sample("PREDICTED"); predicted.setDataSource("PREDICTION"); alerts.upsertCongestionAlert(predicted);
        var smooth=sample("PREDICTED"); smooth.setDataSource("PREDICTION"); smooth.setCongestionLevel(0);
        alerts.upsertCongestionAlert(smooth);
        assertThat(alertRows.countByStatus("UNREAD")).isEqualTo(1);
        assertThat(alertRows.countByStatus("RESOLVED")).isEqualTo(1);
    }

    void concurrently(int count,Runnable action) throws Exception {
        var pool=Executors.newFixedThreadPool(count); var ready=new CountDownLatch(count); var start=new CountDownLatch(1);
        List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int n=0;n<count;n++) jobs.add(pool.submit(()->{ready.countDown();try {start.await();action.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            for(var job:jobs) job.get(15,TimeUnit.SECONDS);
        } finally {start.countDown();pool.shutdownNow();}
    }
}
