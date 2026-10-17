package com.itms.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = false)
public class UserCreateRequest extends StrictRequest {
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}") private String username;
    @NotBlank @Size(min = 6, max = 72) private String password;
    @Size(max = 100) private String realName;
    @Email @Size(max = 100) private String email;
    @Pattern(regexp = "[0-9+() -]{0,20}") private String phone;
    @NotNull @Pattern(regexp = "ADMIN|TRAFFIC_MANAGER|DRIVER") private String role = "DRIVER";
    @NotNull private Boolean enabled = true;
    @Size(max = 100) private String district;
    @Size(max = 500) private String avatarUrl;
}
