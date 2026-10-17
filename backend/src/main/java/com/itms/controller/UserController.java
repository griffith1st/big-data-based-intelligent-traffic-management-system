package com.itms.controller;

import com.itms.common.Result;
import com.itms.dto.*;
import com.itms.service.UserManagementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "管理员维护账号，响应不包含密码")
public class UserController {
    private final UserManagementService users;
    @GetMapping("/list")
    public Result<List<UserProfile>> listUsers() { return Result.success(users.list()); }
    @GetMapping("/page")
    public Result<Page<UserProfile>> page(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return Result.success(users.page(page, size));
    }
    @PostMapping
    public Result<UserProfile> createUser(@Valid @RequestBody UserCreateRequest request) {
        return Result.success("用户创建成功", users.create(request));
    }
    @PutMapping("/{id}")
    public Result<UserProfile> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return Result.success("用户更新成功", users.update(id, request));
    }
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        users.delete(id); return Result.success("用户删除成功", null);
    }
}
