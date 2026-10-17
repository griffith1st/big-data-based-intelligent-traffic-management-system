package com.itms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体类 - 交通管理员、司机、系统管理员
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    /** Incremented when credentials change so existing tokens expire immediately. */
    @Column(name = "auth_version", nullable = false)
    @Builder.Default
    private Long authVersion = 0L;

    @Column(name = "real_name", length = 100)
    private String realName;

    @Column(length = 100, unique = true)
    private String email;

    @Column(length = 20)
    private String phone;

    /** 角色: ADMIN(系统管理员), TRAFFIC_MANAGER(交通管理员), DRIVER(司机) */
    @Column(nullable = false, length = 30)
    private String role;

    /** 状态: 0-禁用, 1-启用 */
    @Column(nullable = false)
    private Boolean enabled;

    /** 所属区域 */
    @Column(length = 100)
    private String district;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

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
