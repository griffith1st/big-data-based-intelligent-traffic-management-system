package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "system_logs", indexes = {
    @Index(name = "idx_log_user", columnList = "user_id"),
    @Index(name = "idx_log_time", columnList = "created_at"),
    @Index(name = "idx_log_type", columnList = "log_type")
})
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作用户ID */
    @Column(name = "user_id")
    private Long userId;

    /** 用户名 */
    @Column(length = 50)
    private String username;

    /** 操作类型: LOGIN(登录), OPERATION(操作), SYSTEM(系统), ERROR(错误) */
    @Column(name = "log_type", length = 20)
    private String logType;

    /** 操作模块 */
    @Column(length = 100)
    private String module;

    /** 操作描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 请求IP */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /** 请求方法 */
    @Column(length = 10)
    private String method;

    /** 请求路径 */
    @Column(length = 500)
    private String requestUri;

    /** 请求参数 */
    @Column(columnDefinition = "TEXT")
    private String requestParams;

    /** 执行时长（毫秒） */
    @Column(name = "execution_time")
    private Long executionTime;

    /** 操作状态: SUCCESS, FAILED */
    @Column(length = 10)
    private String status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
