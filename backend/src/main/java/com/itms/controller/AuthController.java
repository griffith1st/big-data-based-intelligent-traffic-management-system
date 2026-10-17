package com.itms.controller;

import com.itms.common.Result;
import com.itms.dto.LoginRequest;
import com.itms.dto.LoginResponse;
import com.itms.dto.UserProfile;
import com.itms.security.UserPrincipal;
import com.itms.service.UserManagementService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.itms.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 - 登录/登出接口
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "用户登录、登出接口")
public class AuthController {

    private final AuthService authService;
    private final UserManagementService users;

    @GetMapping("/me")
    @Operation(summary = "获取当前身份", description = "从数据库读取当前用户资料与角色")
    public Result<UserProfile> me(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(users.profile(principal.userId()));
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名和密码登录系统")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Result.success("登录成功", response);
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "用户退出系统")
    public Result<Void> logout(@RequestHeader("Authorization") String token) {
        authService.logout(token);
        return Result.success("登出成功", null);
    }
}
