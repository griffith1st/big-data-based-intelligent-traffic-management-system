package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.repository.RoutePlanRepository;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.SystemLogService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RouteTrafficFreshnessTest {
    private final TrafficDataRepository data = mock(TrafficDataRepository.class);
    private final RoutePlanRepository plans = mock(RoutePlanRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RoutePlanServiceImpl service = new RoutePlanServiceImpl(plans, data, json, mock(SystemLogService.class));

    private TrafficData sample(String intersection, int level, LocalDateTime timestamp) {
        return TrafficData.builder().intersectionId(intersection).direction("NORTH")
                .congestionLevel(level).recordTime(timestamp).build();
    }
    private void verifyEdge(List<TrafficData> samples, String status, Integer level) throws Exception {
        when(data.findLatestTrafficData()).thenReturn(samples);
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var plan = service.planRouteByIntersection("INT-001", "INT-004", "SHORTEST", 1L);
        var edges = json.readTree(plan.getRouteEdges());
        assertThat(edges.size()).isEqualTo(1);
        assertThat(edges.get(0).path("trafficDataStatus").asText()).isEqualTo(status);
        if (level == null) {
            assertThat(edges.get(0).path("congestionLevel").isNull()).isTrue();
            assertThat(edges.get(0).path("congestionLevelText").asText()).isEqualTo("UNKNOWN");
        } else {
            assertThat(edges.get(0).path("congestionLevel").asInt()).isEqualTo(level);
        }
        assertThat(plan.getEstimatedTime()).isPositive();
        assertThat(plan.getCarbonEmission()).isPositive();
    }
    @Test void emptyDatabaseStillPlansButDoesNotInventSmoothTraffic() throws Exception {
        verifyEdge(List.of(), "UNAVAILABLE", null);
    }
    @Test void expiredSamplesDoNotDescribeTheCurrentRouteAsSmooth() throws Exception {
        verifyEdge(List.of(sample("INT-001", 0, LocalDateTime.now().minusMinutes(10)),
                sample("INT-004", 0, LocalDateTime.now().minusMinutes(10))), "UNAVAILABLE", null);
    }
    @Test void missingEndpointDisclosesPartialDataInsteadOfInventingItsCondition() throws Exception {
        verifyEdge(List.of(sample("INT-001", 3, LocalDateTime.now())), "PARTIAL", null);
    }
    @Test void sampledEndpointsCanDescribeObservedCongestion() throws Exception {
        verifyEdge(List.of(sample("INT-001", 1, LocalDateTime.now()),
                sample("INT-004", 3, LocalDateTime.now())), "LIVE", 3);
    }
}
