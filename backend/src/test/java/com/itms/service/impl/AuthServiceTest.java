package com.itms.service.impl;

import com.itms.dto.LoginRequest;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.service.RedisAuthService;
import com.itms.service.SystemLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static com.itms.support.Fixtures.jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    UserRepository users = mock(UserRepository.class);
    RedisAuthService redis = mock(RedisAuthService.class);
    AuthServiceImpl service = new AuthServiceImpl(users, jwt(60_000), mock(SystemLogService.class), redis);
    User user;
    @BeforeEach void setup() {
        user = User.builder().id(1L).username("alice").enabled(true).role("ADMIN")
                .password(new BCryptPasswordEncoder().encode("correct-password")).build();
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(users.recordLogin(eq(1L), eq(0L), any())).thenReturn(1);
    }
    LoginRequest login(String username, String password) {
        var request = new LoginRequest(); request.setUsername(username); request.setPassword(password); return request;
    }
    @Test void acceptsBcryptPasswordAndReturnsIdentityWithoutPlainPassword() {
        var response = service.login(login("alice", "correct-password"));
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(user.getLastLoginTime()).isNotNull();
    }
    @Test void normalLoginRejectsUnmigratedPlaintextPassword() {
        user.setPassword("correct-password");
        assertThatThrownBy(() -> service.login(login("alice", "correct-password")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
    }
    @Test void unknownUserConsumesTheSameFailureBudget() {
        assertThatThrownBy(() -> service.login(login("missing", "wrong")))
                .isInstanceOf(ResponseStatusException.class);
        verify(redis).recordLoginFailure("missing");
    }
    @Test void lockedAccountRejectsEvenCorrectPasswordWithRemainingRetryAfter() {
        when(redis.loginRetryAfterSeconds("alice")).thenReturn(381L);
        assertThatThrownBy(() -> service.login(login("alice", "correct-password")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    var error = (ResponseStatusException) ex;
                    assertThat(error.getStatusCode().value()).isEqualTo(429);
                    assertThat(error.getHeaders().getFirst("Retry-After")).isEqualTo("381");
                });
        verify(users, never()).save(any());
    }
    @Test void concurrentAccountChangeRejectsLoginInsteadOfIssuingStaleToken() {
        when(users.recordLogin(eq(1L), eq(0L), any())).thenReturn(0);
        assertThatThrownBy(() -> service.login(login("alice", "correct-password")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
    }
}
