package com.itms.service;

import com.itms.entity.RoutePlan;

import java.util.List;
import java.util.Map;

public interface RoutePlanService {

    RoutePlan planRoute(Double startLng, Double startLat,
                        Double endLng, Double endLat, String strategy, Long userId);

    RoutePlan planRouteByIntersection(String startIntersectionId, String endIntersectionId,
                                      String strategy, Long userId);

    List<RoutePlan> getUserRoutes(Long userId);

    List<RoutePlan> getAllRoutes();

    RoutePlan planGreenRoute(Double startLng, Double startLat,
                             Double endLng, Double endLat, Long userId);

    List<Map<String, Object>> getIntersections();

    Map<String, Object> getNetwork();
}
