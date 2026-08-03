package com.itms.config;

import com.itms.entity.User;
import com.itms.repository.UserRepository;
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

    @Value("${itms.demo.reset-default-users:true}")
    private boolean resetDefaultUsers;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureUser("admin", "系统管理员", "admin@itms.com", "ADMIN", "全城");
        ensureUser("manager1", "张三", "zhangsan@itms.com", "TRAFFIC_MANAGER", "朝阳区");
        ensureUser("manager2", "李四", "lisi@itms.com", "TRAFFIC_MANAGER", "海淀区");
        ensureUser("driver1", "王五", "wangwu@itms.com", "DRIVER", "朝阳区");
        ensureUser("driver2", "赵六", "zhaoliu@itms.com", "DRIVER", "海淀区");
    }

    private void ensureUser(String username, String realName, String email, String role, String district) {
        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            if (resetDefaultUsers) {
                user.setPassword(DEFAULT_PASSWORD);
                user.setEnabled(true);
                user.setRole(role);
                user.setRealName(realName);
                user.setEmail(email);
                user.setDistrict(district);
            }
        }, () -> userRepository.save(User.builder()
                .username(username)
                .password(DEFAULT_PASSWORD)
                .realName(realName)
                .email(email)
                .role(role)
                .enabled(true)
                .district(district)
                .build()));
    }
}
