package com.itms.service;
import com.itms.dto.*;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.security.Passwords;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class UserManagementService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final SystemLogService audit;

    public List<UserProfile> list() {
        return users.findAll(Sort.by("id")).stream().map(UserProfile::from).toList();
    }
    public Page<UserProfile> page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw error(HttpStatus.BAD_REQUEST, "page须大于等于0，size范围为1至100");
        return users.findAll(PageRequest.of(page, size, Sort.by("id"))).map(UserProfile::from);
    }
    public UserProfile profile(Long id) { return UserProfile.from(find(id)); }

    @Transactional
    public UserProfile create(UserCreateRequest request) {
        lockMutations();
        if (users.existsByUsername(request.getUsername())) throw error(HttpStatus.CONFLICT, "用户名已存在");
        String email = normalized(request.getEmail());
        if (email != null && users.existsByEmail(email)) throw error(HttpStatus.CONFLICT, "邮箱已存在");
        validatePassword(request.getPassword());
        User user = users.saveAndFlush(User.builder().username(request.getUsername())
                .password(Passwords.encode(request.getPassword())).realName(request.getRealName())
                .email(email).phone(request.getPhone()).role(request.getRole()).enabled(request.getEnabled())
                .district(request.getDistrict()).avatarUrl(request.getAvatarUrl()).build());
        audit.log("OPERATION", "用户管理", "创建用户 id=" + user.getId(), "SUCCESS");
        return UserProfile.from(user);
    }

    @Transactional
    public UserProfile update(Long id, UserUpdateRequest request) {
        lockMutations();
        User user = find(id);
        if (request.getUsername() != null && !user.getUsername().equals(request.getUsername()))
            throw error(HttpStatus.BAD_REQUEST, "用户名创建后保持固定");
        if (!"ADMIN".equals(request.getRole()) || !Boolean.TRUE.equals(request.getEnabled())) protectLastAdmin(user);
        String email = normalized(request.getEmail());
        if (email != null && users.existsByEmailAndIdNot(email, id)) throw error(HttpStatus.CONFLICT, "邮箱已存在");
        boolean credentialChanged = false;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            validatePassword(request.getPassword()); user.setPassword(Passwords.encode(request.getPassword()));
            credentialChanged = true;
        }
        if (Boolean.TRUE.equals(user.getEnabled()) && !Boolean.TRUE.equals(request.getEnabled())) credentialChanged = true;
        if (credentialChanged) user.setAuthVersion(user.getAuthVersion() + 1);
        user.setRealName(request.getRealName()); user.setEmail(email); user.setPhone(request.getPhone());
        user.setRole(request.getRole()); user.setEnabled(request.getEnabled());
        user.setDistrict(request.getDistrict()); user.setAvatarUrl(request.getAvatarUrl());
        user = users.saveAndFlush(user);
        audit.log("OPERATION", "用户管理", "更新用户 id=" + id + (credentialChanged ? "（旧令牌已失效）" : ""), "SUCCESS");
        return UserProfile.from(user);
    }

    @Transactional
    public void delete(Long id) {
        lockMutations(); User user = find(id); protectLastAdmin(user);
        users.delete(user); users.flush();
        audit.log("OPERATION", "用户管理", "删除用户 id=" + id, "SUCCESS");
    }
    private User find(Long id) { return users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "用户不存在")); }
    private void protectLastAdmin(User user) {
        if ("ADMIN".equals(user.getRole()) && Boolean.TRUE.equals(user.getEnabled()) && users.countByRoleAndEnabledTrue("ADMIN") <= 1)
            throw error(HttpStatus.CONFLICT, "至少保留一名启用的系统管理员");
    }
    private void lockMutations() {
        // Low-frequency administration: serialize writes across instances before counting admins.
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
    }
    private void validatePassword(String password) {
        if (!Passwords.validNewPassword(password)) throw error(HttpStatus.BAD_REQUEST, "密码至少6个字符且UTF-8编码不超过72字节");
    }
    private String normalized(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
