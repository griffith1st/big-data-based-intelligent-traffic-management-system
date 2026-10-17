package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交通流量预测实体
 * 存储趋势模拟器生成的未来30分钟交通流量数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "traffic_predictions", indexes = {
    @Index(name = "idx_pred_intersection", columnList = "intersection_id"),
    @Index(name = "idx_pred_time", columnList = "prediction_time"),
    @Index(name = "idx_pred_batch", columnList = "prediction_batch_id")
})
public class TrafficPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 路口编号 */
    @Column(name = "intersection_id", nullable = false, length = 50)
    private String intersectionId;

    @Column(name = "intersection_name", length = 200)
    private String intersectionName;

    @Column(name = "prediction_batch_id", length = 40)
    private String predictionBatchId;

    /** 预测时间点 */
    @Column(name = "prediction_time", nullable = false)
    private LocalDateTime predictionTime;

    /** 预测车流量 */
    @Column(name = "predicted_volume")
    private Integer predictedVolume;

    /** 预测平均速度 */
    @Column(name = "predicted_speed")
    private Double predictedSpeed;

    /** 预测拥堵等级 */
    @Column(name = "predicted_congestion")
    private Integer predictedCongestion;

    /** 保留兼容字段；模拟器不产生经过校准的置信度，值为null */
    @Column(name = "confidence")
    private Double confidence;

    /** 模型版本 */
    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
