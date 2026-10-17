package com.itms.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = false)
public class UserUpdateRequest extends StrictRequest {
    @Size(max = 50) private String username;
    @Size(max = 72) private String password;
    @Size(max = 100) private String realName;
    @Email @Size(max = 100) private String email;
    @Pattern(regexp = "[0-9+() -]{0,20}") private String phone;
    @NotNull @Pattern(regexp = "ADMIN|TRAFFIC_MANAGER|DRIVER") private String role;
    @NotNull private Boolean enabled;
    @Size(max = 100) private String district;
    @Size(max = 500) private String avatarUrl;
}
