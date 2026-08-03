package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交通信号灯实体
 * 记录每个路口的信号灯配时方案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "traffic_signals")
public class TrafficSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 路口编号 */
    @Column(name = "intersection_id", nullable = false, length = 50)
    private String intersectionId;

    /** 方向: NORTH, SOUTH, EAST, WEST */
    @Column(length = 10)
    private String direction;

    /** 绿灯时长（秒） */
    @Column(name = "green_duration")
    private Integer greenDuration;

    /** 红灯时长（秒） */
    @Column(name = "red_duration")
    private Integer redDuration;

    /** 黄灯时长（秒） */
    @Column(name = "yellow_duration")
    private Integer yellowDuration;

    /** 周期时长（秒） */
    @Column(name = "cycle_duration")
    private Integer cycleDuration;

    /** 相位编号 */
    @Column(name = "phase_id")
    private Integer phaseId;

    /** 优化状态: PENDING, OPTIMIZED, APPLIED */
    @Column(name = "optimization_status", length = 20)
    private String optimizationStatus;

    /** 优化策略: DQN, FIXED, ADAPTIVE */
    @Column(name = "strategy", length = 20)
    private String strategy;

    /** 应用时间 */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
