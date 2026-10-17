package com.itms.config;

import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.security.Passwords;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DefaultUserInitializer implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "123456";

    private final UserRepository userRepository;

    @Value("${itms.demo.reset-default-users:false}")
    private boolean resetDefaultUsers;

    @Value("${itms.demo.enabled:true}")
    private boolean demoEnabled = true;

    @Value("${itms.auth.migrate-legacy-passwords:true}")
    private boolean migrateLegacyPasswords = true;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // One-way compatibility migration only at startup, never in the login path.
        // Disable after all existing credentials have been converted.
        if (migrateLegacyPasswords) {
            for (User user : userRepository.findAll()) {
                if (!Passwords.isHash(user.getPassword())) {
                    // Preserve existing credentials during an upgrade; minimum length
                    // applies to newly chosen passwords, not this one-way conversion.
                    if (user.getPassword() == null || user.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                        throw new IllegalStateException("Legacy password requires administrator reset for user " + user.getId());
                    }
                    user.setPassword(Passwords.encode(user.getPassword()));
                    user.setAuthVersion(user.getAuthVersion() + 1);
                    userRepository.save(user);
                }
            }
        }
        if (!demoEnabled) return;
        ensureUser("admin", "系统管理员", "admin@itms.com", "ADMIN", "全城");
        ensureUser("manager1", "张三", "zhangsan@itms.com", "TRAFFIC_MANAGER", "朝阳区");
        ensureUser("manager2", "李四", "lisi@itms.com", "TRAFFIC_MANAGER", "海淀区");
        ensureUser("driver1", "王五", "wangwu@itms.com", "DRIVER", "朝阳区");
        ensureUser("driver2", "赵六", "zhaoliu@itms.com", "DRIVER", "海淀区");
    }

    private void ensureUser(String username, String realName, String email, String role, String district) {
        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            if (resetDefaultUsers) {
                user.setPassword(Passwords.encode(DEFAULT_PASSWORD));
                user.setAuthVersion(user.getAuthVersion() + 1);
                user.setEnabled(true);
                user.setRole(role);
                user.setRealName(realName);
                user.setEmail(email);
                user.setDistrict(district);
            }
        }, () -> userRepository.save(User.builder()
                .username(username)
                .password(Passwords.encode(DEFAULT_PASSWORD))
                .realName(realName)
                .email(email)
                .role(role)
                .enabled(true)
                .district(district)
                .build()));
    }
}
