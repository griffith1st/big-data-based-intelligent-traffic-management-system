package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.*;
import com.itms.repository.*;
import com.itms.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.itms.support.Fixtures.traffic;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficBusinessContractTest {
    TrafficDataRepository db = mock(TrafficDataRepository.class);
    TrafficAlertService alerts = mock(TrafficAlertService.class);
    SystemLogService logs = mock(SystemLogService.class);
    TrafficDataServiceImpl trafficService = new TrafficDataServiceImpl(db, alerts, mock(StringRedisTemplate.class), new ObjectMapper());

    @Test void realtimeReadDoesNotCreateSeedRecords() {
        assertThat(trafficService.getRealTimeData()).isEmpty();
        verify(db, never()).save(any());
    }

    @Test void invalidTrafficIsRejectedBeforePersistence() {
        List<TrafficData> invalid = new ArrayList<>();
        var a=traffic(10); a.setId(5L); invalid.add(a);
        a=traffic(10); a.setIntersectionId("unknown"); invalid.add(a);
        a=traffic(10); a.setDirection("PREDICTED"); invalid.add(a);
        a=traffic(-1); invalid.add(a);
        a=traffic(10); a.setAvgSpeed(Double.NaN); invalid.add(a);
        a=traffic(10); a.setOccupancyRate(101.0); invalid.add(a);
        a=traffic(10); a.setCongestionLevel(4); invalid.add(a);
        for (var data:invalid) assertThatThrownBy(()->trafficService.saveTrafficData(data)).isInstanceOf(IllegalArgumentException.class);
        verify(db, never()).save(any());
    }

    @Test void batchValidatesEveryRowBeforeFirstSaveAndHasBoundedSize() {
        assertThatThrownBy(()->trafficService.batchSave(List.of(traffic(10),traffic(-1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->trafficService.batchSave(Collections.nCopies(501,traffic(10)))).isInstanceOf(IllegalArgumentException.class);
        verify(db, never()).save(any());
    }

    @Test void historyRejectsUnknownIntersectionAndInvalidWindow() {
        assertThatThrownBy(()->trafficService.getHistoryData("unknown",60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->trafficService.getHistoryData("INT-001",-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->trafficService.getHistoryData("INT-001",Integer.valueOf(1001))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void predictionReadDoesNotTriggerWriteAndReportsSimulationHonestly() {
        var repo=mock(TrafficPredictionRepository.class);
        when(repo.save(any())).thenAnswer(i->i.getArgument(0));
        var service=new TrafficPredictionServiceImpl(repo,db,alerts,logs);
        ReflectionTestUtils.setField(service,"predictionWindow",30);
        assertThat(service.getPredictions("INT-001")).isEmpty();
        verify(repo,never()).save(any());
        assertThat(service.getLatestPredictionBatch("INT-001")).containsEntry("modelVersion","SIMULATION-v1");
    }

    @Test void predictionBatchUsesUniqueIdAndNoInventedConfidence() {
        var repo=mock(TrafficPredictionRepository.class);
        when(repo.save(any())).thenAnswer(i->i.getArgument(0));
        var service=new TrafficPredictionServiceImpl(repo,db,alerts,logs);
        ReflectionTestUtils.setField(service,"predictionWindow",5);
        var result=service.triggerPrediction();
        assertThatCode(()->UUID.fromString((String)result.get("batchId"))).doesNotThrowAnyException();
        var items=(List<Map<String,Object>>)result.get("items");
        assertThat(items).isNotEmpty().allSatisfy(item->{
            assertThat(item.get("confidence")).isNull();
            assertThat(item.get("modelVersion")).isEqualTo("SIMULATION-v1");
        });
    }

    @Test void predictionDatabaseFailurePropagatesInsteadOfFabricatingSuccess() {
        when(db.findLatestTrafficData()).thenThrow(new IllegalStateException("db down"));
        var service=new TrafficPredictionServiceImpl(mock(TrafficPredictionRepository.class),db,alerts,logs);
        assertThatThrownBy(service::triggerPrediction).hasMessage("db down");
    }

    @Test void signalOptimizationConservesPhaseCycleAndKeepsActiveDurationsUntilApply() {
        var repo=mock(TrafficSignalRepository.class);
        var active=new ArrayList<TrafficSignal>();
        for(String direction:List.of("NORTH","SOUTH","EAST","WEST"))
            active.add(TrafficSignal.builder().intersectionId("INT-001").direction(direction).greenDuration(40).redDuration(45).yellowDuration(5).cycleDuration(90).optimizationStatus("PENDING").build());
        when(repo.findByIntersectionId("INT-001")).thenReturn(active);
        when(repo.save(any())).thenAnswer(i->i.getArgument(0));
        var north=traffic(100000); north.setCongestionLevel(3); north.setAvgSpeed(1.0);
        when(db.findLatestTrafficData()).thenReturn(List.of(north));
        var service=new TrafficSignalServiceImpl(repo,db,logs);
        var result=service.optimizeSignals("INT-001","CONGESTION_FIRST");
        var phases=(List<Map<String,Object>>)result.get("phases");
        int sum=phases.stream().mapToInt(p->((Number)p.get("greenDuration")).intValue()+((Number)p.get("yellowDuration")).intValue()).sum();
        assertThat(sum).isEqualTo(result.get("cycleLength"));
        assertThat(active).allSatisfy(signal->assertThat(signal.getGreenDuration()).isEqualTo(40));
        var candidates=(List<TrafficSignal>)result.get("signals");
        assertThat(candidates).allSatisfy(signal->assertThat(signal.getAppliedAt()).isNull());
    }

    @Test void signalRejectsUnknownIntersectionStrategyAndApplyWithoutCandidate() {
        var service=new TrafficSignalServiceImpl(mock(TrafficSignalRepository.class),db,logs);
        assertThatThrownBy(()->service.getSignals("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.optimizeSignals("INT-001","DQN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.applySignalPlan("INT-001")).isInstanceOf(RuntimeException.class);
    }
    @Test void legacySignalPlanWithInvalidCycleIsReportedAndNotSilentlyApplied() {
        var repo=mock(TrafficSignalRepository.class); var signals=new ArrayList<TrafficSignal>();
        for(String direction:List.of("NORTH","SOUTH","EAST","WEST"))
            signals.add(TrafficSignal.builder().intersectionId("INT-001").direction(direction).greenDuration(45)
                    .redDuration(30).yellowDuration(10).cycleDuration(95).optimizationStatus("APPLIED").build());
        when(repo.findByIntersectionId("INT-001")).thenReturn(signals);
        var service=new TrafficSignalServiceImpl(repo,db,logs);
        assertThat(service.getCurrent("INT-001")).containsEntry("planValid",false);
        assertThatThrownBy(()->service.applySignalPlan("INT-001")).isInstanceOf(RuntimeException.class);
    }

    @Test void signalDirectionInputsDiscloseObservedVersusBaselineData() {
        var repo=mock(TrafficSignalRepository.class); var list=new ArrayList<TrafficSignal>();
        for(String direction:List.of("NORTH","SOUTH","EAST","WEST"))
            list.add(TrafficSignal.builder().intersectionId("INT-001").direction(direction).greenDuration(40)
                .redDuration(45).yellowDuration(5).cycleDuration(90).build());
        when(repo.findByIntersectionId("INT-001")).thenReturn(list);
        var north=traffic(100); north.setRecordTime(java.time.LocalDateTime.now());
        var south=traffic(900); south.setDirection("SOUTH"); south.setRecordTime(java.time.LocalDateTime.now().minusHours(1));
        when(db.findLatestTrafficData()).thenReturn(List.of(north,south));
        var service=new TrafficSignalServiceImpl(repo,db,logs);
        var tree=new ObjectMapper().findAndRegisterModules().valueToTree(service.getCurrent("INT-001"));
        assertThat(tree.path("directionStates").get(0).path("observed").asBoolean()).isTrue();
        assertThat(tree.path("directionStates").get(1).path("source").asText()).isEqualTo("BASELINE_ESTIMATE");
        assertThat(tree.path("directionStates").get(1).path("recordTime").isNull()).isTrue();
    }

}
