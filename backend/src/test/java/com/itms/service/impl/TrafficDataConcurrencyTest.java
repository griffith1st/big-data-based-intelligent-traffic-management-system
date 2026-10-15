package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrafficDataConcurrencyTest {
    TrafficDataRepository db;
    TrafficAlertService alerts;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    TrafficDataServiceImpl service;
    Map<String, String> cache;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        db = mock(TrafficDataRepository.class);
        alerts = mock(TrafficAlertService.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        cache = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> cache.get(inv.getArgument(0)));
        doAnswer(inv -> { cache.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            for (String key : inv.<java.util.Collection<String>>getArgument(0)) cache.remove(key);
            return 2L;
        });
        when(db.count()).thenReturn(1L);
        service = new TrafficDataServiceImpl(db, alerts, redis, new ObjectMapper().findAndRegisterModules());
    }

    @Test void simultaneousMissesShareOneDatabaseQueryEvenWhenRedisIsDown() throws Exception {
        when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            calls.incrementAndGet(); entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        var threads = new CopyOnWriteArrayList<Thread>();
        var pool = Executors.newFixedThreadPool(8, task -> {
            Thread thread = new Thread(task); threads.add(thread); return thread;
        });
        try {
            var futures = new java.util.ArrayList<Future<List<Map<String, Object>>>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(service::getRealTimeData));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> threads.size() == 8 && threads.stream().allMatch(
                    t -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING));
            release.countDown();
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS).get(0).get("vehicleCount")).isEqualTo(600);
            assertThat(calls.get()).as("concurrent cache misses must coalesce even without Redis").isEqualTo(1);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void readStartedBeforeWriteDoesNotRefillOldCache() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        try {
            var oldRead = pool.submit(service::getRealTimeData);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var update = traffic(900); when(db.save(update)).thenReturn(update);
            service.saveTrafficData(update);
            doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
            release.countDown();
            assertThat(oldRead.get(5, TimeUnit.SECONDS).get(0).get("vehicleCount")).isEqualTo(600);
            assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void summaryAfterWriteDoesNotJoinAnOlderRealtimeFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        try {
            var oldRead = pool.submit(service::getRealTimeData);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var update = traffic(900); when(db.save(update)).thenReturn(update);
            service.saveTrafficData(update);
            doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
            assertThat(service.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
            release.countDown(); oldRead.get(5, TimeUnit.SECONDS);
            assertThat(service.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void followerTimeoutDoesNotRemoveTheOwnersFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            calls.incrementAndGet(); entered.countDown();
            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        try {
            var owner = pool.submit(service::getRealTimeData);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var follower = pool.submit(service::getRealTimeData);
            assertThatThrownBy(() -> follower.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class);
            var laterFollower = pool.submit(service::getRealTimeData);
            assertThatThrownBy(() -> laterFollower.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS).get(0).get("vehicleCount")).isEqualTo(600);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void oldSummaryDependencyDoesNotRefillEitherCacheAfterWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        try {
            var oldSummary = pool.submit(service::getIntersectionSummary);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var update = traffic(900); when(db.save(update)).thenReturn(update);
            service.saveTrafficData(update);
            doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
            release.countDown();
            assertThat(oldSummary.get(5, TimeUnit.SECONDS).get(0).get("totalVehicleCount")).isEqualTo(600);
            assertThat(cache).isEmpty();
            assertThat(service.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void invalidationAndCachePublicationCannotCross() throws Exception {
        var publishing = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        var saved = new CountDownLatch(1);
        var threads = new CopyOnWriteArrayList<Thread>();
        var pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task); threads.add(thread); return thread;
        });
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(600, 25, "NORTH")));
        doAnswer(inv -> {
            publishing.countDown(); assertThat(publish.await(5, TimeUnit.SECONDS)).isTrue();
            cache.put(inv.getArgument(0), inv.getArgument(1)); return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        try {
            var reader = pool.submit(service::getRealTimeData);
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            var update = traffic(900);
            when(db.save(update)).thenAnswer(inv -> { saved.countDown(); return update; });
            var writer = pool.submit(() -> service.saveTrafficData(update));
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> writer.isDone()
                    || threads.stream().anyMatch(t -> t.getState() == Thread.State.BLOCKED));
            publish.countDown(); reader.get(5, TimeUnit.SECONDS); writer.get(5, TimeUnit.SECONDS);
            assertThat(cache).as("the writer's invalidation must follow the older publication").isEmpty();
        } finally {
            publish.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void failedOwnerReleasesFlightForTheNextAttempt() {
        when(db.findRealTimeCongestion()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(service::getRealTimeData).hasMessage("database unavailable");
        doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
        assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900);
    }

    @Test void summaryReadingWhileInvalidationIsInProgressCannotPublishAnOldDependency() throws Exception {
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(600, 25, "NORTH")));
        service.getRealTimeData();
        var deleting = new CountDownLatch(1);
        var allowDelete = new CountDownLatch(1);
        var dependencyRead = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        when(values.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = cache.get(key);
            if (key.equals("itms:traffic:realtime") && deleting.getCount() == 0) dependencyRead.countDown();
            return value;
        });
        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            deleting.countDown(); assertThat(allowDelete.await(5, TimeUnit.SECONDS)).isTrue();
            for (String key : inv.<java.util.Collection<String>>getArgument(0)) cache.remove(key);
            return 2L;
        });
        try {
            var update = traffic(900); when(db.save(update)).thenReturn(update);
            var writer = pool.submit(() -> service.saveTrafficData(update));
            assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
            var summary = pool.submit(service::getIntersectionSummary);
            assertThat(dependencyRead.await(5, TimeUnit.SECONDS)).isTrue();
            allowDelete.countDown(); writer.get(5, TimeUnit.SECONDS);
            assertThat(summary.get(5, TimeUnit.SECONDS).get(0).get("totalVehicleCount")).isEqualTo(600);
            assertThat(cache).as("a summary read during invalidation belongs to the old generation").isEmpty();
            doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
            assertThat(service.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
        } finally {
            allowDelete.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
