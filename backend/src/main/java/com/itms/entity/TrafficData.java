package com.itms.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 实时交通数据实体
 * 记录每个路口/路段的实时车流数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "traffic_data", uniqueConstraints = @UniqueConstraint(name = "uq_traffic_data_event_id", columnNames = "event_id"), indexes = {
    @Index(name = "idx_intersection_time", columnList = "intersection_id,record_time"),
    @Index(name = "idx_record_time", columnList = "record_time")
})
public class TrafficData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable source event identity, shared across transport retries and fallback. */
    @Column(name = "event_id", length = 64)
    private String eventId;

    /** Server-derived fingerprint of original business input, never an API field. */
    @JsonIgnore
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    /** 路口/路段编号 */
    @Column(name = "intersection_id", nullable = false, length = 50)
    private String intersectionId;

    /** 路口名称 */
    @Column(name = "intersection_name", length = 200)
    private String intersectionName;

    /** 经度 */
    @Column(nullable = false)
    private Double longitude;

    /** 纬度 */
    @Column(nullable = false)
    private Double latitude;

    /** 当前采样数量（辆）；采样窗口由采集源约定 */
    @Column(name = "vehicle_count", nullable = false)
    private Integer vehicleCount;

    /** 平均速度（km/h） */
    @Column(name = "avg_speed")
    private Double avgSpeed;

    /** 道路占用率（%） */
    @Column(name = "occupancy_rate")
    private Double occupancyRate;

    /** 拥堵等级: 0-畅通, 1-轻度拥堵, 2-中度拥堵, 3-严重拥堵 */
    @Column(name = "congestion_level")
    private Integer congestionLevel;

    /** 方向: NORTH, SOUTH, EAST, WEST */
    @Column(length = 10)
    private String direction;

    /** 记录时间 */
    @Column(name = "record_time", nullable = false)
    private LocalDateTime recordTime;

    /** 数据来源: SIMULATOR(模拟器), MANUAL(人工), SENSOR/CAMERA/GPS, UNKNOWN(未声明) */
    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
