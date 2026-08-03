package com.itms.service;

import com.itms.dto.LoginRequest;
import com.itms.dto.LoginResponse;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 用户登录
     * @param request 登录请求
     * @return 登录响应（含Token）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 用户登出
     * @param userId 用户ID
     */
    void logout(Long userId);
}
