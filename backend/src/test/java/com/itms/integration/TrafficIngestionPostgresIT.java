package com.itms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficDataRepository;
import com.itms.repository.TrafficIngestionReceiptRepository;
import com.itms.service.TrafficDataService;
import com.itms.service.TrafficIngestionService;
import com.itms.service.impl.TrafficAlertServiceImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL + Redis + Spring transactions. No live Kafka broker is needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "itms.traffic.simulator.enabled=false", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.data.redis.database=1"
})
class TrafficIngestionPostgresIT {
    @DynamicPropertySource static void isolatedServices(DynamicPropertyRegistry registry) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        int pgPort = Integer.parseInt(System.getProperty("test.postgres.port"));
        int redisPort = Integer.parseInt(System.getProperty("test.redis.port"));
        assertThat(pgPort).isBetween(1024, 65535);
        assertThat(redisPort).isBetween(1024, 65535);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + pgPort + "/itms_concurrency_it");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @Autowired TrafficIngestionService ingestion;
    @Autowired TrafficDataService traffic;
    @Autowired TrafficDataRepository data;
    @Autowired TrafficIngestionReceiptRepository receipts;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @SpyBean TrafficAlertServiceImpl alerts;

    @BeforeEach void clearIsolatedState() {
        reset(alerts);
        receipts.deleteAll();
        data.deleteAll();
        redis.delete(java.util.List.of("itms:traffic:realtime", "itms:traffic:intersection-summary"));
    }

    private ConsumerRecord<String, String> record(long offset, int count) throws Exception {
        var event = TrafficData.builder().intersectionId("INT-001").direction("NORTH")
                .recordTime(LocalDateTime.now()).vehicleCount(count).avgSpeed(45.0)
                .congestionLevel(0).dataSource("TEST").build();
        return new ConsumerRecord<>("itms-test-traffic", 0, offset, "INT-001:NORTH", mapper.writeValueAsString(event));
    }

    @Test void concurrentRedeliveryCommitsExactlyOneTrafficRowForTheSameSourcePosition() throws Exception {
        var message = record(101, 600);
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        var tasks = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 12; i++) tasks.add(pool.submit(() -> {
                ready.countDown();
                try { if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                ingestion.ingest(message);
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(data.count()).isEqualTo(1);
        assertThat(traffic.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(600);
    }

    @Test void failedAlertRollsBackReceiptAndTrafficSoTheSameOffsetCanRetry() throws Exception {
        var message = record(102, 700);
        doThrow(new IllegalStateException("injected alert failure")).when(alerts).upsertCongestionAlert(any());
        assertThatThrownBy(() -> ingestion.ingest(message)).hasMessage("injected alert failure");
        assertThat(receipts.count()).isZero();
        assertThat(data.count()).isZero();
        reset(alerts);
        ingestion.ingest(message);
        ingestion.ingest(message);
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(data.count()).isEqualTo(1);
    }

    @Test void committedIngestionInvalidatesBothCachesBeforeTheNextRead() throws Exception {
        ingestion.ingest(record(103, 600));
        traffic.getRealTimeData();
        traffic.getIntersectionSummary();
        assertThat(redis.hasKey("itms:traffic:realtime")).isTrue();
        ingestion.ingest(record(104, 900));
        assertThat(redis.hasKey("itms:traffic:realtime")).isFalse();
        assertThat(redis.hasKey("itms:traffic:intersection-summary")).isFalse();
        assertThat(traffic.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
    }

    @Test void databaseConnectionsHaveBoundedStatementAndLockWaits() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
    }
}
