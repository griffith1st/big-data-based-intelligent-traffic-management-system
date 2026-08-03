package com.itms.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrafficNetwork {

    public static final List<String> DIRECTIONS = List.of("NORTH", "SOUTH", "EAST", "WEST");

    public static final Map<String, Intersection> INTERSECTIONS;
    public static final List<RoadEdge> EDGES;

    static {
        Map<String, Intersection> nodes = new LinkedHashMap<>();
        add(nodes, "INT-001", "天安门路口", 116.397, 39.908, 640);
        add(nodes, "INT-002", "国贸路口", 116.435, 39.908, 820);
        add(nodes, "INT-003", "建国门路口", 116.420, 39.908, 760);
        add(nodes, "INT-004", "东单路口", 116.411, 39.908, 690);
        add(nodes, "INT-005", "朝阳门路口", 116.434, 39.924, 720);
        add(nodes, "INT-006", "西单路口", 116.374, 39.907, 610);
        add(nodes, "INT-007", "中关村路口", 116.316, 39.983, 780);
        add(nodes, "INT-008", "三元桥路口", 116.456, 39.961, 800);
        add(nodes, "INT-009", "复兴门路口", 116.356, 39.907, 590);
        add(nodes, "INT-010", "王府井路口", 116.411, 39.915, 680);
        add(nodes, "INT-011", "东直门路口", 116.435, 39.941, 760);
        add(nodes, "INT-012", "安贞桥路口", 116.407, 39.969, 700);
        add(nodes, "INT-013", "五道口路口", 116.337, 39.992, 760);
        add(nodes, "INT-014", "四惠桥路口", 116.495, 39.908, 820);
        INTERSECTIONS = Collections.unmodifiableMap(nodes);

        List<RoadEdge> roads = new ArrayList<>();
        edge(roads, "INT-009", "INT-006", 1.55, 3.6, 45, "MAIN", 1.05, 1.05, 3);
        edge(roads, "INT-006", "INT-001", 1.95, 4.4, 45, "MAIN", 1.08, 1.05, 4);
        edge(roads, "INT-001", "INT-004", 1.20, 2.8, 40, "SECONDARY", 1.18, 1.10, 5);
        edge(roads, "INT-004", "INT-003", 0.80, 2.1, 35, "SECONDARY", 1.22, 1.12, 4);
        edge(roads, "INT-003", "INT-002", 1.35, 3.2, 45, "MAIN", 1.35, 1.12, 5);
        edge(roads, "INT-002", "INT-014", 5.25, 6.4, 70, "EXPRESS", 0.78, 0.86, 1);

        edge(roads, "INT-001", "INT-010", 0.95, 2.5, 35, "SECONDARY", 1.16, 1.12, 4);
        edge(roads, "INT-010", "INT-005", 2.45, 5.4, 40, "MAIN", 1.12, 1.04, 4);
        edge(roads, "INT-005", "INT-011", 1.85, 4.0, 45, "MAIN", 1.06, 1.02, 3);
        edge(roads, "INT-011", "INT-008", 3.20, 5.6, 60, "MAIN", 0.92, 0.96, 2);
        edge(roads, "INT-008", "INT-012", 4.55, 7.2, 60, "MAIN", 0.90, 0.94, 2);

        edge(roads, "INT-006", "INT-007", 10.20, 18.4, 55, "MAIN", 0.96, 0.98, 5);
        edge(roads, "INT-007", "INT-013", 2.45, 5.2, 35, "SECONDARY", 1.10, 1.03, 4);
        edge(roads, "INT-013", "INT-012", 6.65, 9.1, 70, "EXPRESS", 0.76, 0.84, 1);

        edge(roads, "INT-004", "INT-010", 0.78, 2.2, 30, "SECONDARY", 1.28, 1.15, 4);
        edge(roads, "INT-003", "INT-005", 1.95, 4.3, 40, "MAIN", 1.30, 1.10, 4);
        edge(roads, "INT-002", "INT-005", 1.55, 3.7, 35, "SECONDARY", 1.45, 1.16, 5);
        edge(roads, "INT-005", "INT-008", 4.60, 8.8, 50, "MAIN", 1.18, 1.03, 4);
        edge(roads, "INT-003", "INT-011", 4.10, 7.0, 55, "MAIN", 1.02, 0.98, 3);

        edge(roads, "INT-009", "INT-007", 10.45, 14.8, 70, "EXPRESS", 0.72, 0.82, 1);
        edge(roads, "INT-007", "INT-012", 8.90, 12.0, 70, "EXPRESS", 0.74, 0.82, 1);
        edge(roads, "INT-012", "INT-011", 3.55, 6.0, 50, "MAIN", 1.00, 0.96, 2);
        edge(roads, "INT-011", "INT-014", 6.95, 9.0, 70, "EXPRESS", 0.80, 0.86, 1);
        edge(roads, "INT-002", "INT-008", 6.20, 9.3, 55, "MAIN", 1.05, 0.98, 3);
        edge(roads, "INT-001", "INT-003", 2.00, 4.6, 40, "SECONDARY", 1.25, 1.12, 5);

        edge(roads, "INT-010", "INT-011", 3.35, 6.7, 45, "MAIN", 1.08, 1.00, 3);
        edge(roads, "INT-006", "INT-010", 3.35, 7.2, 35, "SECONDARY", 1.30, 1.16, 6);
        edge(roads, "INT-004", "INT-005", 2.55, 5.6, 35, "SECONDARY", 1.34, 1.18, 5);
        edge(roads, "INT-014", "INT-008", 6.45, 8.2, 70, "EXPRESS", 0.82, 0.84, 1);
        EDGES = Collections.unmodifiableList(roads);
    }

    private TrafficNetwork() {
    }

    private static void add(Map<String, Intersection> nodes, String id, String name,
                            double longitude, double latitude, int baseFlow) {
        nodes.put(id, new Intersection(id, name, longitude, latitude, baseFlow));
    }

    private static void edge(List<RoadEdge> roads, String from, String to, double distanceKm,
                             double baseTimeMin, int speedLimit, String roadType,
                             double congestionWeight, double carbonFactor, int trafficLightCount) {
        roads.add(new RoadEdge(from, to, distanceKm, baseTimeMin, speedLimit, roadType,
                congestionWeight, carbonFactor, trafficLightCount));
    }

    public static Intersection intersection(String id) {
        return INTERSECTIONS.get(id);
    }

    public record Intersection(String intersectionId, String intersectionName,
                               double longitude, double latitude, int baseFlow) {
    }

    public record RoadEdge(String from, String to, double distanceKm, double baseTimeMin,
                           int speedLimit, String roadType, double congestionWeight,
                           double carbonFactor, int trafficLightCount) {
    }
}
