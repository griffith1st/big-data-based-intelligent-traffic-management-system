package com.itms.service.impl;

import com.itms.dto.LoginRequest;
import com.itms.dto.LoginResponse;
import com.itms.entity.User;
import com.itms.entity.SystemLog;
import com.itms.security.Passwords;
import com.itms.security.LoginLockedException;
import com.itms.repository.UserRepository;
import com.itms.service.AuthService;
import com.itms.service.RedisAuthService;
import com.itms.service.SystemLogService;
import com.itms.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DUMMY_PASSWORD = Passwords.encode("invalid-user-placeholder");

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final SystemLogService systemLogService;
    private final RedisAuthService redisAuthService;

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        long retry = redisAuthService.loginRetryAfterSeconds(username);
        if (retry > 0) throw new LoginLockedException(retry);
        User user = userRepository.findByUsername(username).orElse(null);
        boolean correctPassword = Passwords.matches(request.getPassword(), user == null ? DUMMY_PASSWORD : user.getPassword());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !correctPassword) {
            try {
                long failures = redisAuthService.recordLoginFailure(username);
                if (failures >= 5) throw new LoginLockedException(redisAuthService.loginRetryAfterSeconds(username));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证状态服务暂时不可用，请稍后重试", e);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号已被禁用");
        }
        try {
            redisAuthService.clearLoginFailures(username);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证状态服务暂时不可用，请稍后重试", e);
        }

        // 记录最近登录时间
        user.setLastLoginTime(LocalDateTime.now());
        if (userRepository.recordLogin(user.getId(), user.getAuthVersion(), user.getLastLoginTime()) == 0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号状态已改变，请重新登录");
        systemLogService.log(SystemLog.builder().userId(user.getId()).username(user.getUsername())
                .logType("LOGIN").module("认证管理").description("用户登录").status("SUCCESS").build());

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion());

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
    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return;
        }
        redisAuthService.revokeToken(authorizationHeader.substring(7).trim());
        systemLogService.log("LOGIN", "认证管理", "用户登出", "SUCCESS");
    }
}

