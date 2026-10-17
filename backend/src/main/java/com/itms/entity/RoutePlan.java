package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 路径规划实体
 * 记录 Dijkstra 在静态示例路网及可用交通快照上计算的路线
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "route_plans")
public class RoutePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "start_intersection_id", length = 50)
    private String startIntersectionId;

    @Column(name = "start_intersection_name", length = 200)
    private String startIntersectionName;

    @Column(name = "end_intersection_id", length = 50)
    private String endIntersectionId;

    @Column(name = "end_intersection_name", length = 200)
    private String endIntersectionName;

    /** 起点经度 */
    @Column(name = "start_lng", nullable = false)
    private Double startLng;

    /** 起点纬度 */
    @Column(name = "start_lat", nullable = false)
    private Double startLat;

    /** 终点经度 */
    @Column(name = "end_lng", nullable = false)
    private Double endLng;

    /** 终点纬度 */
    @Column(name = "end_lat", nullable = false)
    private Double endLat;

    /** 优化策略: SHORTEST(最短路径), GREENEST(低碳), FASTEST(最快) */
    @Column(name = "strategy", length = 20)
    private String strategy;

    /** 路径坐标点集（JSON格式） */
    @Column(name = "route_points", columnDefinition = "TEXT")
    private String routePoints;

    /** 总距离（km） */
    @Column(name = "total_distance")
    private Double totalDistance;

    /** 预计时间（分钟） */
    @Column(name = "estimated_time")
    private Integer estimatedTime;

    /** 估算碳排放量（g） */
    @Column(name = "carbon_emission")
    private Double carbonEmission;

    /** 经过路口列表（JSON格式） */
    @Column(name = "intersections", columnDefinition = "TEXT")
    private String intersections;

    @Column(name = "route_edges", columnDefinition = "TEXT")
    private String routeEdges;

    @Column(length = 30)
    private String algorithm;
    @Column(name = "traffic_data_status", length = 20)
    private String trafficDataStatus;
    @Column(name = "sampled_intersection_count")
    private Integer sampledIntersectionCount;
    @Column(name = "sampled_direction_count")
    private Integer sampledDirectionCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
