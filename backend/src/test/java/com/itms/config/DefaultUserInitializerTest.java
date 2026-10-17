package com.itms.config;

import com.itms.entity.User;
import com.itms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultUserInitializerTest {
    @Test void migratesExistingPlainPasswordWithoutResettingProfileOrDisabledStatus() {
        var users = mock(UserRepository.class);
        var existing = User.builder().id(1L).username("admin").password("chosen-password")
                .enabled(false).role("DRIVER").realName("Existing user").build();
        when(users.findAll()).thenReturn(List.of(existing));
        when(users.findByUsername("admin")).thenReturn(Optional.of(existing));
        var initializer = new DefaultUserInitializer(users);
        ReflectionTestUtils.setField(initializer, "resetDefaultUsers", false);
        initializer.run(new DefaultApplicationArguments());
        assertThat(new BCryptPasswordEncoder().matches("chosen-password", existing.getPassword())).isTrue();
        assertThat(existing.getEnabled()).isFalse();
        assertThat(existing.getRole()).isEqualTo("DRIVER");
        assertThat(existing.getRealName()).isEqualTo("Existing user");
    }
    @Test void createsMissingDemoUsersWithHashes() {
        var users = mock(UserRepository.class);
        List<User> saved = new ArrayList<>();
        when(users.save(any())).thenAnswer(call -> { User user = call.getArgument(0); saved.add(user); return user; });
        var initializer = new DefaultUserInitializer(users);
        initializer.run(new DefaultApplicationArguments());
        assertThat(saved).hasSize(5).allSatisfy(user ->
                assertThat(new BCryptPasswordEncoder().matches("123456", user.getPassword())).isTrue());
    }
    @Test void migrationPreservesShortLegacyPasswordWhileNewPasswordPolicyIsStricter() {
        var users = mock(UserRepository.class);
        var existing = User.builder().id(7L).username("legacy").password("short").enabled(true).role("DRIVER").build();
        when(users.findAll()).thenReturn(List.of(existing));
        var initializer = new DefaultUserInitializer(users);
        ReflectionTestUtils.setField(initializer, "demoEnabled", false);
        initializer.run(new DefaultApplicationArguments());
        assertThat(new BCryptPasswordEncoder().matches("short", existing.getPassword())).isTrue();
        assertThat(existing.getAuthVersion()).isEqualTo(1);
    }
}
