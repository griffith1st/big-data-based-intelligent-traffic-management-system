package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises application transaction callbacks; DB operations are the external boundary. */
class TrafficDataTransactionTest {
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
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(900, 20, "NORTH")));
        service = new TrafficDataServiceImpl(db, alerts, redis, new ObjectMapper().findAndRegisterModules());
    }

    void begin() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void transactionWriteInvalidatesOnlyAfterCommit() {
        cache.put("itms:traffic:realtime", "[{\"vehicleCount\":600}]");
        begin();
        var update = traffic(900); when(db.save(update)).thenReturn(update);
        service.saveTrafficData(update);
        assertThat(cache).containsKey("itms:traffic:realtime");
        verify(redis, never()).delete(anyCollection());
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(cache).isEmpty();
        verify(redis).delete(List.of("itms:traffic:realtime", "itms:traffic:intersection-summary"));
    }

    @Test void rolledBackWriteDoesNotInvalidateCommittedCache() {
        cache.put("itms:traffic:realtime", "[{\"vehicleCount\":600}]");
        begin();
        var update = traffic(900); when(db.save(update)).thenReturn(update);
        service.saveTrafficData(update);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(cache).containsEntry("itms:traffic:realtime", "[{\"vehicleCount\":600}]");
        verify(redis, never()).delete(anyCollection());
    }

    @Test void transactionReadBypassesCacheAndDoesNotPublishItsOwnSnapshot() {
        cache.put("itms:traffic:realtime", "[{\"vehicleCount\":600}]");
        cache.put("itms:traffic:intersection-summary", "[{\"totalVehicleCount\":600}]");
        begin();
        assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900);
        assertThat(service.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
        verifyNoInteractions(redis);
        assertThat(cache.get("itms:traffic:realtime")).contains("600");
        assertThat(cache.get("itms:traffic:intersection-summary")).contains("600");
    }

    @Test void alertFailureInsideRolledBackTransactionDoesNotEvict() {
        begin();
        var update = traffic(900); when(db.save(update)).thenReturn(update);
        doThrow(new IllegalStateException("alert failure")).when(alerts).upsertCongestionAlert(update);
        assertThatThrownBy(() -> service.saveTrafficData(update)).hasMessage("alert failure");
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis, never()).delete(anyCollection());
    }

    @Test void transactionReadDoesNotJoinNonTransactionalFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Object[]>of(row(600, 25, "NORTH"));
        });
        try {
            var outside = pool.submit(service::getRealTimeData);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            doReturn(List.<Object[]>of(row(900, 20, "NORTH"))).when(db).findRealTimeCongestion();
            begin();
            assertThatCode(() -> assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900))
                    .doesNotThrowAnyException();
            assertThat(cache).isEmpty();
            release.countDown(); outside.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
