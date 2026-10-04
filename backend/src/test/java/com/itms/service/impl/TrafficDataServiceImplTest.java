package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.List;
import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficDataServiceImplTest {
    static final String REALTIME = "itms:traffic:realtime";
    static final String SUMMARY = "itms:traffic:intersection-summary";
    TrafficDataRepository db;
    TrafficAlertService alerts;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    ObjectMapper mapper;
    TrafficDataServiceImpl service;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        db = mock(TrafficDataRepository.class);
        alerts = mock(TrafficAlertService.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new TrafficDataServiceImpl(db, alerts, redis, mapper);
    }
    void databaseRows() {
        when(db.count()).thenReturn(1L);
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(600, 25, "NORTH")));
    }
    // A deleted hit branch must cause a DB call or a wrong result.
    @Test void cacheHitReturnsPayloadWithoutDatabaseAccess() {
        when(values.get(REALTIME)).thenReturn("[{\"vehicleCount\":17,\"intersectionId\":\"INT-001\"}]");
        assertThat(service.getRealTimeData()).singleElement().satisfies(r -> assertThat(r.get("vehicleCount")).isEqualTo(17));
        verifyNoInteractions(db);
    }
    @Test void cacheMissMapsDatabaseProjectionAndCachesJson() throws Exception {
        databaseRows();
        var result = service.getRealTimeData();
        assertThat(result.get(0)).containsEntry("vehicleCount", 600).containsEntry("averageSpeed", 25.0)
                .containsEntry("congestionLevelText", "MEDIUM").containsEntry("direction", "NORTH");
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(REALTIME), json.capture(), eq(Duration.ofSeconds(5)));
        assertThat(mapper.readTree(json.getValue()).get(0).get("vehicleCount").asInt()).isEqualTo(600);
    }
    @Test void malformedJsonFallsBackToDatabase() {
        databaseRows(); when(values.get(REALTIME)).thenReturn("{broken-json");
        assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(600);
    }
    @Test void redisReadAndWriteOutageStillReturnsDatabaseResult() {
        databaseRows(); when(values.get(REALTIME)).thenThrow(new IllegalStateException("redis read outage"));
        doThrow(new IllegalStateException("redis write outage")).when(values).set(eq(REALTIME), anyString(), any(Duration.class));
        assertThat(service.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(600);
    }
    @Test void databaseFailureIsNotHiddenAsEmptySuccess() {
        when(db.count()).thenReturn(1L);
        when(db.findRealTimeCongestion()).thenThrow(new IllegalStateException("database down"));
        assertThatThrownBy(service::getRealTimeData).hasMessage("database down");
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void summaryUsesWeightedSpeedAndWorstCongestion() {
        when(db.count()).thenReturn(2L);
        when(db.findRealTimeCongestion()).thenReturn(List.of(row(100, 10, "NORTH"), row(300, 30, "SOUTH")));
        var summary = service.getIntersectionSummary();
        assertThat(summary).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("totalVehicleCount", 400).containsEntry("averageSpeed", 25.0)
                .containsEntry("directionCount", 2).containsEntry("worstCongestionLevelValue", 2));
    }
    @Test void saveNormalizesAndInvalidatesBothCachesAfterDatabaseAndAlert() {
        var data = traffic(700); when(db.save(data)).thenReturn(data);
        assertThat(service.saveTrafficData(data)).isSameAs(data);
        assertThat(data.getRecordTime()).isNotNull();
        assertThat(data.getCongestionLevel()).isEqualTo(1);
        var order = inOrder(db, alerts, redis);
        order.verify(db).save(data); order.verify(alerts).upsertCongestionAlert(data);
        order.verify(redis).delete(List.of(REALTIME, SUMMARY));
    }
    @Test void cacheDeleteFailureDoesNotDiscardSuccessfulSave() {
        var data = traffic(700); when(db.save(data)).thenReturn(data);
        when(redis.delete(List.of(REALTIME, SUMMARY))).thenThrow(new IllegalStateException("redis unavailable"));
        assertThat(service.saveTrafficData(data)).isSameAs(data);
        verify(db).save(data);
    }
    @Test void failedDatabaseSaveDoesNotPublishAlertOrInvalidateCache() {
        when(db.save(any())).thenThrow(new IllegalStateException("write failed"));
        assertThatThrownBy(() -> service.saveTrafficData(traffic(700))).hasMessage("write failed");
        verifyNoInteractions(alerts); verify(redis, never()).delete(anyCollection());
    }
    @Test @Tag("known-limitation") void characterizesAlertFailureSkippingInvalidationAfterSave() {
        var data = traffic(700); when(db.save(data)).thenReturn(data);
        doThrow(new IllegalStateException("alert failed")).when(alerts).upsertCongestionAlert(data);
        assertThatThrownBy(() -> service.saveTrafficData(data)).hasMessage("alert failed");
        verify(db).save(data); verify(redis, never()).delete(anyCollection());
    }
}
