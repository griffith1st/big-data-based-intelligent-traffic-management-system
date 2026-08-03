package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户增删改查")
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/list")
    @Operation(summary = "获取用户列表")
    public Result<List<Map<String, Object>>> listUsers() {
        return Result.success(userRepository.findAll().stream()
                .map(this::toSafeMap)
                .toList());
    }

    @PostMapping
    @Operation(summary = "创建用户")
    public Result<Map<String, Object>> createUser(@RequestBody User user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()
                && userRepository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("邮箱已存在");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            user.setPassword("123456");
        }
        if (user.getEnabled() == null) {
            user.setEnabled(true);
        }
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("DRIVER");
        }
        return Result.success("用户创建成功", toSafeMap(userRepository.save(user)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户")
    public Result<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody User request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        user.setRealName(request.getRealName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole(request.getRole());
        user.setDistrict(request.getDistrict());
        user.setEnabled(request.getEnabled());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(request.getPassword());
        }
        return Result.success("用户更新成功", toSafeMap(userRepository.save(user)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public Result<Void> deleteUser(@PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            throw new IllegalArgumentException("用户不存在");
        }
        userRepository.deleteById(id);
        return Result.success("用户删除成功", null);
    }

    private Map<String, Object> toSafeMap(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("realName", user.getRealName());
        map.put("email", user.getEmail());
        map.put("phone", user.getPhone());
        map.put("role", user.getRole());
        map.put("enabled", user.getEnabled());
        map.put("district", user.getDistrict());
        map.put("avatarUrl", user.getAvatarUrl());
        map.put("lastLoginTime", user.getLastLoginTime());
        map.put("createdAt", user.getCreatedAt());
        map.put("updatedAt", user.getUpdatedAt());
        return map;
    }
}
