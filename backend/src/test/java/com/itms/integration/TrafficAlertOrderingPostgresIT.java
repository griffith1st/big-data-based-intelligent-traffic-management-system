package com.itms.integration;

import com.itms.entity.TrafficData;
import com.itms.repository.TrafficAlertRepository;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import com.itms.service.TrafficDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Traffic rows are the ordering watermark even before the first congestion alert exists. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "itms.traffic.simulator.enabled=false","spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false","spring.jpa.hibernate.ddl-auto=update",
    "spring.datasource.username=postgres","spring.datasource.password=","spring.data.redis.database=1",
    "itms.demo.reset-default-users=false"
})
class TrafficAlertOrderingPostgresIT {
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        int pg=Integer.parseInt(System.getProperty("test.postgres.port"));
        int redis=Integer.parseInt(System.getProperty("test.redis.port"));
        assertThat(pg).isBetween(1024,65535);assertThat(redis).isBetween(1024,65535);
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+pg+"/itms_concurrency_it");
        r.add("spring.data.redis.port",()->redis);
    }
    @Autowired TrafficDataService traffic;
    @Autowired TrafficAlertService alerts;
    @Autowired TrafficDataRepository rows;
    @Autowired TrafficAlertRepository alertRows;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void clean() {
        alertRows.deleteAll();rows.deleteAll();
        redis.delete(List.of("itms:traffic:realtime","itms:traffic:intersection-summary"));
    }
    TrafficData observation(String direction,int level,LocalDateTime time) {
        return TrafficData.builder().intersectionId("INT-001").intersectionName("test intersection")
            .direction(direction).vehicleCount(level==0?100:900).avgSpeed(level==0?45.0:12.0)
            .congestionLevel(level).recordTime(time).longitude(116.397).latitude(39.908)
            .dataSource("TEST").build();
    }
    @Test void firstSmoothObservationPreventsLateOlderCongestionFromOpeningAlert() {
        var now=LocalDateTime.now();
        traffic.saveTrafficData(observation("NORTH",0,now));
        traffic.saveTrafficData(observation("NORTH",3,now.minusMinutes(1)));
        assertThat(rows.findLatestTrafficData()).singleElement().satisfies(row->assertThat(row.getCongestionLevel()).isZero());
        assertThat(alertRows.count()).isZero();
    }
    @Test void olderIdAtSameTimestampCannotReopenAfterNewerSmoothObservation() {
        var time=LocalDateTime.now().withNano(0);
        var older=rows.save(observation("NORTH",3,time));
        var newer=traffic.saveTrafficData(observation("NORTH",0,time));
        assertThat(newer.getId()).isGreaterThan(older.getId());
        alerts.upsertCongestionAlert(older);
        assertThat(alertRows.count()).isZero();
    }
    @Test void olderIdAtSameTimestampCannotResolveNewerCongestion() {
        var time=LocalDateTime.now().withNano(0);
        var older=rows.save(observation("NORTH",0,time));
        traffic.saveTrafficData(observation("NORTH",3,time));
        alerts.upsertCongestionAlert(older);
        assertThat(alertRows.countByStatus("UNREAD")).isEqualTo(1);
        assertThat(alertRows.countByStatus("RESOLVED")).isZero();
    }
    @Test void largerIdAtSameTimestampCanRepresentNewCongestionAfterRecovery() {
        var time=LocalDateTime.now().withNano(0);
        traffic.saveTrafficData(observation("NORTH",3,time));
        traffic.saveTrafficData(observation("NORTH",0,time));
        traffic.saveTrafficData(observation("NORTH",3,time));
        assertThat(alertRows.countByStatus("RESOLVED")).isEqualTo(1);
        assertThat(alertRows.countByStatus("UNREAD")).isEqualTo(1);
    }
    @Test void realtimeWatermarkDoesNotSuppressPredictedRiskOrAnotherDirection() {
        var now=LocalDateTime.now();
        traffic.saveTrafficData(observation("NORTH",0,now));
        var prediction=observation("PREDICTED",3,now.minusMinutes(1));prediction.setDataSource("PREDICTION");
        alerts.upsertCongestionAlert(prediction);
        traffic.saveTrafficData(observation("SOUTH",3,now.minusMinutes(1)));
        traffic.saveTrafficData(observation("NORTH",3,now.minusMinutes(1)));
        assertThat(alertRows.findAll()).hasSize(2).extracting(a->a.getSource()+":"+a.getDirection())
            .containsExactlyInAnyOrder("PREDICTION:PREDICTED","REALTIME:SOUTH");
    }
    @Test void waitingOlderWriterRechecksTrafficAfterConditionLockIsAcquired() throws Exception {
        var now=LocalDateTime.now();var pool=Executors.newFixedThreadPool(2);
        var smoothWritten=new CountDownLatch(1);var commitSmooth=new CountDownLatch(1);
        try {
            var smooth=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                traffic.saveTrafficData(observation("NORTH",0,now));
                smoothWritten.countDown();
                try {assertThat(commitSmooth.await(5,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            assertThat(smoothWritten.await(5,TimeUnit.SECONDS)).isTrue();
            var stale=pool.submit(()->traffic.saveTrafficData(observation("NORTH",3,now.minusMinutes(1))));
            await().atMost(Duration.ofMillis(1500)).pollInterval(Duration.ofMillis(25)).until(()->
                jdbc.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid " +
                    "WHERE l.locktype='advisory' AND NOT l.granted AND a.datname=current_database()",Long.class)>0);
            commitSmooth.countDown();smooth.get(10,TimeUnit.SECONDS);stale.get(10,TimeUnit.SECONDS);
            assertThat(rows.count()).isEqualTo(2);
            assertThat(alertRows.count()).isZero();
        } finally {commitSmooth.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
}
