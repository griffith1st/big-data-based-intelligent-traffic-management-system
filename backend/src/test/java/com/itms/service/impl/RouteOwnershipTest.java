package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.controller.RoutePlanController;
import com.itms.controller.AlertController;
import com.itms.entity.RoutePlan;
import com.itms.repository.*;
import com.itms.service.*;
import com.itms.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteOwnershipTest {
    @BeforeEach void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new UserPrincipal(42L,"operator","OPERATOR",0L),null,List.of()));
    }
    @AfterEach void logout(){ SecurityContextHolder.clearContext(); }
    @Test void routeOwnerComesFromAuthenticationAndCrossUserHistoryIsRejected() {
        var service=mock(RoutePlanService.class); var controller=new RoutePlanController(service);
        controller.planRouteByIntersection(Map.of("startIntersectionId","INT-001","endIntersectionId","INT-002"));
        verify(service).planRouteByIntersection("INT-001","INT-002","SHORTEST",42L);
        assertThatThrownBy(()->controller.routeHistory(7L)).isInstanceOf(ResponseStatusException.class);
        controller.routeHistory(null); verify(service).getUserRoutes(42L); verify(service,never()).getAllRoutes();
    }
    @Test void clientCannotImpersonateAlertHandler() {
        var service=mock(TrafficAlertService.class); var controller=new AlertController(service);
        controller.resolveAlertNew(1L,"forged-user");
        verify(service).resolveAlert(1L,"operator");
    }
    @Test void routeRejectsUnknownStrategyAndInvalidCoordinates() {
        var service=new RoutePlanServiceImpl(mock(RoutePlanRepository.class),mock(TrafficDataRepository.class),new ObjectMapper(),mock(SystemLogService.class));
        assertThatThrownBy(()->service.planRouteByIntersection("INT-001","INT-002","MAGIC",42L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.planRoute(Double.NaN,40.0,116.0,40.0,"SHORTEST",42L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shortestUsesPhysicalDistanceAndMissingTrafficIsExplicit() {
        var routes=mock(RoutePlanRepository.class); var traffic=mock(TrafficDataRepository.class);
        when(routes.save(any())).thenAnswer(i->i.getArgument(0));
        var mapper=new ObjectMapper(); var service=new RoutePlanServiceImpl(routes,traffic,mapper,mock(SystemLogService.class));
        var empty=service.planRouteByIntersection("INT-001","INT-005","SHORTEST",42L);
        assertThat(mapper.valueToTree(empty).path("trafficDataStatus").asText()).isEqualTo("UNAVAILABLE");
        var busy=com.itms.support.Fixtures.traffic(900); busy.setIntersectionId("INT-010"); busy.setCongestionLevel(3);
        busy.setRecordTime(java.time.LocalDateTime.now());
        when(traffic.findLatestTrafficData()).thenReturn(List.of(busy));
        when(traffic.findCurrentCongestionNative()).thenReturn(List.<Object[]>of(new Object[]{"INT-010",3,3.0}));
        var route=service.planRouteByIntersection("INT-001","INT-005","SHORTEST",42L);
        assertThat(route.getTotalDistance()).isEqualTo(3.4);
        var json=mapper.valueToTree(route);
        assertThat(json.path("trafficDataStatus").asText()).isEqualTo("PARTIAL");
        assertThat(json.path("sampledIntersectionCount").asInt()).isEqualTo(1);
        assertThat(json.path("algorithm").asText()).isEqualTo("DIJKSTRA");
    }

}
