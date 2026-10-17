package com.itms.dto;
import com.itms.entity.User;
import java.time.LocalDateTime;
/** Public account data only; never serialize the persistence entity into an API response. */
public record UserProfile(Long id, Long userId, String username, String realName, String email,
        String phone, String role, Boolean enabled, String district, String avatarUrl,
        LocalDateTime lastLoginTime, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getId(), user.getUsername(), user.getRealName(), user.getEmail(),
                user.getPhone(), user.getRole(), user.getEnabled(), user.getDistrict(), user.getAvatarUrl(),
                user.getLastLoginTime(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
