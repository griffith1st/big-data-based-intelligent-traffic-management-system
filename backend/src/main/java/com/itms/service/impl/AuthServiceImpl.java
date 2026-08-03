package com.itms.service.impl;

import com.itms.dto.LoginRequest;
import com.itms.dto.LoginResponse;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.service.AuthService;
import com.itms.service.SystemLogService;
import com.itms.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final SystemLogService systemLogService;

    @Override
    public LoginResponse login(LoginRequest request) {
        // 查找用户
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        // 检查用户状态
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new RuntimeException("账户已被禁用，请联系管理员");
        }

        // 验证密码（生产环境应使用BCrypt加密）
        if (!user.getPassword().equals(request.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 记录最近登录时间
        user.setLastLoginTime(LocalDateTime.now());
        userRepository.save(user);
        systemLogService.log("LOGIN", "认证管理", "用户登录: " + user.getUsername(), "SUCCESS");

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .district(user.getDistrict())
                .build();
    }

    @Override
    public void logout(Long userId) {
        // JWT无状态登出，客户端清除Token即可
        // 可在Redis中维护黑名单实现
    }
}

