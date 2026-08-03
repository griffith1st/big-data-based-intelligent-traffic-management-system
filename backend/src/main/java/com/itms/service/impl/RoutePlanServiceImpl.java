package com.itms.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.common.TrafficNetwork;
import com.itms.entity.RoutePlan;
import com.itms.entity.TrafficData;
import com.itms.repository.RoutePlanRepository;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.RoutePlanService;
import com.itms.service.SystemLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutePlanServiceImpl implements RoutePlanService {

    private final RoutePlanRepository routePlanRepository;
    private final TrafficDataRepository trafficDataRepository;
    private final ObjectMapper objectMapper;
    private final SystemLogService systemLogService;

    @Override
    public RoutePlan planRoute(Double startLng, Double startLat,
                               Double endLng, Double endLat, String strategy, Long userId) {
        return planRouteByIntersection(nearestIntersection(startLng, startLat),
                nearestIntersection(endLng, endLat), strategy, userId);
    }

    @Override
    public RoutePlan planRouteByIntersection(String startIntersectionId, String endIntersectionId,
                                             String strategy, Long userId) {
        TrafficNetwork.Intersection start = TrafficNetwork.intersection(startIntersectionId);
        TrafficNetwork.Intersection end = TrafficNetwork.intersection(endIntersectionId);
        if (start == null || end == null) {
            throw new IllegalArgumentException("Unknown intersection");
        }

        String normalizedStrategy = normalizeStrategy(strategy);
        Map<String, Double> congestionByIntersection = currentCongestionByIntersection();
        List<String> pathIds = dijkstra(startIntersectionId, endIntersectionId,
                normalizedStrategy, congestionByIntersection);
        if (pathIds.isEmpty()) {
            throw new IllegalStateException("No route found");
        }

        List<Map<String, Object>> path = pathIds.stream().map(id -> {
            TrafficNetwork.Intersection node = TrafficNetwork.intersection(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intersectionId", node.intersectionId());
            item.put("intersectionName", node.intersectionName());
            item.put("longitude", node.longitude());
            item.put("latitude", node.latitude());
            return item;
        }).toList();

        List<Map<String, Object>> routeEdges = new ArrayList<>();
        double distance = 0;
        double time = 0;
        double carbon = 0;
        for (int i = 0; i < pathIds.size() - 1; i++) {
            TrafficNetwork.RoadEdge edge = edgeBetween(pathIds.get(i), pathIds.get(i + 1));
            if (edge == null) {
                continue;
            }
            double dynamicWeight = dynamicCongestionWeight(edge, congestionByIntersection);
            double avgCongestion = Math.max(congestionByIntersection.getOrDefault(edge.from(), 0.0),
                    congestionByIntersection.getOrDefault(edge.to(), 0.0));
            int congestionLevel = (int) Math.round(avgCongestion);
            double estimatedTime = edge.baseTimeMin() * dynamicWeight + edge.trafficLightCount() * 0.5;
            double estimatedCarbon = edge.distanceKm() * 120.0 * edge.carbonFactor() * dynamicWeight
                    + edge.trafficLightCount() * 8.0;
            distance += edge.distanceKm();
            time += estimatedTime;
            carbon += estimatedCarbon;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", pathIds.get(i));
            item.put("to", pathIds.get(i + 1));
            item.put("distanceKm", round(edge.distanceKm(), 2));
            item.put("estimatedTimeMin", round(estimatedTime, 1));
            item.put("carbonEmissionGram", round(estimatedCarbon, 1));
            item.put("baseTimeMin", edge.baseTimeMin());
            item.put("speedLimit", edge.speedLimit());
            item.put("roadType", edge.roadType());
            item.put("congestionWeight", round(dynamicWeight, 2));
            item.put("baseCongestionWeight", edge.congestionWeight());
            item.put("carbonFactor", edge.carbonFactor());
            item.put("trafficLightCount", edge.trafficLightCount());
            item.put("congestionLevel", congestionLevel);
            item.put("congestionLevelText", TrafficDataServiceImpl.congestionText(congestionLevel));
            routeEdges.add(item);
        }

        RoutePlan routePlan = RoutePlan.builder()
                .userId(userId)
                .startIntersectionId(start.intersectionId())
                .startIntersectionName(start.intersectionName())
                .endIntersectionId(end.intersectionId())
                .endIntersectionName(end.intersectionName())
                .startLng(start.longitude())
                .startLat(start.latitude())
                .endLng(end.longitude())
                .endLat(end.latitude())
                .strategy(normalizedStrategy)
                .routePoints(toJson(path))
                .intersections(toJson(path))
                .routeEdges(toJson(routeEdges))
                .totalDistance(round(distance, 2))
                .estimatedTime((int) Math.round(time))
                .carbonEmission(round(carbon, 1))
                .build();

        RoutePlan saved = routePlanRepository.save(routePlan);
        systemLogService.log("OPERATION", "路线规划",
                "生成路线 " + start.intersectionName() + " -> " + end.intersectionName(), "SUCCESS");
        return saved;
    }

    @Override
    public List<RoutePlan> getUserRoutes(Long userId) {
        return routePlanRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public List<RoutePlan> getAllRoutes() {
        return routePlanRepository.findAll();
    }

    @Override
    public RoutePlan planGreenRoute(Double startLng, Double startLat,
                                    Double endLng, Double endLat, Long userId) {
        return planRoute(startLng, startLat, endLng, endLat, "LOW_CARBON", userId);
    }

    @Override
    public List<Map<String, Object>> getIntersections() {
        return TrafficNetwork.INTERSECTIONS.values().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intersectionId", node.intersectionId());
            item.put("intersectionName", node.intersectionName());
            item.put("longitude", node.longitude());
            item.put("latitude", node.latitude());
            return item;
        }).toList();
    }

    @Override
    public Map<String, Object> getNetwork() {
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("nodes", getIntersections());
        network.put("edges", TrafficNetwork.EDGES.stream().map(edge -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", edge.from());
            item.put("to", edge.to());
            item.put("distanceKm", edge.distanceKm());
            item.put("baseTimeMin", edge.baseTimeMin());
            item.put("speedLimit", edge.speedLimit());
            item.put("roadType", edge.roadType());
            item.put("congestionWeight", edge.congestionWeight());
            item.put("carbonFactor", edge.carbonFactor());
            item.put("trafficLightCount", edge.trafficLightCount());
            return item;
        }).toList());
        return network;
    }

    private List<String> dijkstra(String start, String end, String strategy,
                                  Map<String, Double> congestionByIntersection) {
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();
        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingDouble(id -> distances.getOrDefault(id, Double.MAX_VALUE)));

        for (String id : TrafficNetwork.INTERSECTIONS.keySet()) {
            distances.put(id, Double.MAX_VALUE);
        }
        distances.put(start, 0.0);
        queue.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(end)) {
                break;
            }
            for (TrafficNetwork.RoadEdge edge : adjacent(current)) {
                String next = edge.from().equals(current) ? edge.to() : edge.from();
                double candidate = distances.get(current) + weight(edge, strategy, congestionByIntersection);
                if (candidate < distances.getOrDefault(next, Double.MAX_VALUE)) {
                    distances.put(next, candidate);
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }

        if (!start.equals(end) && !previous.containsKey(end)) {
            return List.of();
        }
        ArrayDeque<String> stack = new ArrayDeque<>();
        String cursor = end;
        stack.push(cursor);
        while (!cursor.equals(start)) {
            cursor = previous.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            stack.push(cursor);
        }
        return new ArrayList<>(stack);
    }

    private double weight(TrafficNetwork.RoadEdge edge, String strategy,
                          Map<String, Double> congestionByIntersection) {
        double congestionWeight = dynamicCongestionWeight(edge, congestionByIntersection);
        return switch (strategy) {
            case "FASTEST" -> edge.baseTimeMin() * congestionWeight + edge.trafficLightCount() * 0.5;
            case "GREENEST", "LOW_CARBON" -> edge.distanceKm() * edge.carbonFactor() * congestionWeight
                    + edge.trafficLightCount() * 0.2;
            default -> {
                double level = Math.max(congestionByIntersection.getOrDefault(edge.from(), 0.0),
                        congestionByIntersection.getOrDefault(edge.to(), 0.0));
                double factor = 1.0 + level * 0.2;
                yield edge.distanceKm() * factor;
            }
        };
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "SHORTEST";
        }
        String upper = strategy.toUpperCase();
        return "GREENEST".equals(upper) ? "LOW_CARBON" : upper;
    }

    /** 返回连续拥堵平均值（0.0-3.0），确保每次查询都能反映真实交通数据变化 */
    private Map<String, Double> currentCongestionByIntersection() {
        Map<String, Double> result = new HashMap<>();

        // 优先使用原生SQL（返回max和avg，使用avg作为连续拥堵值）
        List<Object[]> nativeRows = trafficDataRepository.findCurrentCongestionNative();
        if (!nativeRows.isEmpty()) {
            for (Object[] row : nativeRows) {
                String intersectionId = (String) row[0];
                double avgLevel = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
                result.put(intersectionId, avgLevel);
            }
            log.info("Route planning using native congestion (avg): {} intersections sampled", result.size());
            return result;
        }

        // 回退：JPQL查询（使用平均值而非最大值，提高对拥堵变化的敏感度）
        Map<String, Double> sumMap = new HashMap<>();
        Map<String, Integer> cntMap = new HashMap<>();
        for (TrafficData data : trafficDataRepository.findLatestTrafficData()) {
            String id = data.getIntersectionId();
            double level = data.getCongestionLevel() == null ? 0.0 : data.getCongestionLevel();
            sumMap.merge(id, level, Double::sum);
            cntMap.merge(id, 1, Integer::sum);
        }
        if (!sumMap.isEmpty()) {
            for (String id : sumMap.keySet()) {
                result.put(id, sumMap.get(id) / cntMap.get(id));
            }
            log.info("Route planning using JPQL congestion (avg): {} intersections sampled", result.size());
            return result;
        }

        // 数据库完全无数据时，按时间生成动态估算拥堵等级
        log.warn("No traffic data in database, using time-based congestion estimates");
        LocalDateTime now = LocalDateTime.now();
        int baseLevel = timeBasedCongestionLevel();
        for (String id : TrafficNetwork.INTERSECTIONS.keySet()) {
            int seed = id.hashCode() + now.getMinute() + now.getHour() * 60;
            int variation = Math.abs(seed) % 4;
            double level = Math.min(3.0, Math.max(0.0, baseLevel - 1.0 + variation));
            result.put(id, level);
        }
        return result;
    }

    /** 根据当前时段估算基础拥堵等级：0=畅通, 1=轻度, 2=中度, 3=严重 */
    private int timeBasedCongestionLevel() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour >= 7 && hour < 9 || hour >= 17 && hour < 19) return 3;  // 高峰期
        if (hour >= 9 && hour < 11 || hour >= 14 && hour < 17) return 1; // 平峰
        if (hour >= 11 && hour < 14) return 2;  // 午间
        if (hour >= 0 && hour < 5) return 0;    // 深夜
        return 1;  // 其他时段轻度拥堵
    }

    /** 使用连续拥堵值插值计算动态权重，避免离散等级造成的阶梯效应 */
    private double dynamicCongestionWeight(TrafficNetwork.RoadEdge edge, Map<String, Double> congestionByIntersection) {
        double level = Math.max(congestionByIntersection.getOrDefault(edge.from(), 0.0),
                congestionByIntersection.getOrDefault(edge.to(), 0.0));
        double realtime = 1.0 + level * 0.4;
        return edge.congestionWeight() * realtime;
    }

    private List<TrafficNetwork.RoadEdge> adjacent(String intersectionId) {
        return TrafficNetwork.EDGES.stream()
                .filter(edge -> edge.from().equals(intersectionId) || edge.to().equals(intersectionId))
                .toList();
    }

    private TrafficNetwork.RoadEdge edgeBetween(String a, String b) {
        return TrafficNetwork.EDGES.stream()
                .filter(edge -> (edge.from().equals(a) && edge.to().equals(b))
                        || (edge.from().equals(b) && edge.to().equals(a)))
                .findFirst()
                .orElse(null);
    }

    private String nearestIntersection(double lng, double lat) {
        return TrafficNetwork.INTERSECTIONS.values().stream()
                .min(Comparator.comparingDouble(node ->
                        Math.pow(node.longitude() - lng, 2) + Math.pow(node.latitude() - lat, 2)))
                .map(TrafficNetwork.Intersection::intersectionId)
                .orElseThrow();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize route", e);
        }
    }

    private double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}
