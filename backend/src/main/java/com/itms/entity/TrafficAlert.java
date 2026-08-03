package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交通告警实体
 * 记录拥堵预警、事故告警等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "traffic_alerts", indexes = {
    @Index(name = "idx_alert_level", columnList = "alert_level"),
    @Index(name = "idx_alert_time", columnList = "created_at")
})
public class TrafficAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 告警内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 告警级别: INFO(信息), WARNING(警告), CRITICAL(严重) */
    @Column(name = "alert_level", nullable = false, length = 20)
    private String alertLevel;

    /** 告警类型: CONGESTION(拥堵), ACCIDENT(事故), SIGNAL(信号异常), WEATHER(天气) */
    @Column(name = "alert_type", length = 30)
    private String alertType;

    /** 相关路口编号 */
    @Column(name = "intersection_id", length = 50)
    private String intersectionId;

    /** 经度 */
    private Double longitude;

    /** 纬度 */
    private Double latitude;

    /** 状态: UNREAD(未读), READ(已读), RESOLVED(已处理) */
    @Column(nullable = false, length = 20)
    private String status;

    /** 处理人 */
    @Column(name = "handler", length = 50)
    private String handler;

    /** 处理时间 */
    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
