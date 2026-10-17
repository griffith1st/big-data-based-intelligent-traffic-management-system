package com.itms.config;

import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SecurityBoundaryTest.Probe.class)
@Import({SecurityConfig.class, SecurityBoundaryTest.Probe.class, SecurityBoundaryTest.Beans.class})
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockBean RedisAuthService redis;
    @MockBean UserRepository users;
    User user;
    @TestConfiguration static class Beans {
        @Bean JwtUtil jwtUtil() { return com.itms.support.Fixtures.jwt(60_000); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }
    @RestController static class Probe {
        @GetMapping({"/api/auth/me", "/api/users/list", "/api/logs", "/actuator/metrics", "/api/traffic/realtime"})
        String read() { return "ok"; }
        @PostMapping({"/api/traffic/save", "/api/routes/plan", "/api/predictions/trigger", "/api/signals/INT-001/apply"})
        String write() { return "ok"; }
    }
    @BeforeEach void setup() {
        user = User.builder().id(1L).username("alice").enabled(true).role("DRIVER").build();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
    }
    String bearer() { return "Bearer " + jwt.generateToken(1L, "alice", "ADMIN"); }
    @Test void anonymousIdentityAndBusinessReadsReturnJson401() throws Exception {
        for (String path : new String[]{"/api/auth/me", "/api/traffic/realtime"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
    }
    @Test void currentDatabaseRoleOverridesOldAdminClaim() throws Exception {
        for (String path : new String[]{"/api/users/list", "/api/logs", "/actuator/metrics"}) {
            mvc.perform(get(path).header("Authorization", bearer()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        }
        mvc.perform(get("/api/traffic/realtime").header("Authorization", bearer())).andExpect(status().isOk());
    }
    @Test void driverCanPlanOwnRouteButCannotChangeTrafficOrSignal() throws Exception {
        mvc.perform(post("/api/routes/plan").header("Authorization", bearer())).andExpect(status().isOk());
        for (String path : new String[]{"/api/traffic/save", "/api/predictions/trigger", "/api/signals/INT-001/apply"}) {
            mvc.perform(post(path).header("Authorization", bearer())).andExpect(status().isForbidden());
        }
    }
    @Test void disabledAndDeletedUsersLoseExistingTokenImmediately() throws Exception {
        user.setEnabled(false);
        mvc.perform(get("/api/traffic/realtime").header("Authorization", bearer())).andExpect(status().isUnauthorized());
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        mvc.perform(get("/api/traffic/realtime").header("Authorization", bearer())).andExpect(status().isUnauthorized());
    }
    @Test void managerCanChangeTrafficButCannotManageAccounts() throws Exception {
        user.setRole("TRAFFIC_MANAGER");
        mvc.perform(post("/api/traffic/save").header("Authorization", bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/predictions/trigger").header("Authorization", bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/users/list").header("Authorization", bearer())).andExpect(status().isForbidden());
    }
    @Test void adminCanReadAdministrativeResourcesAndChangedPasswordInvalidatesToken() throws Exception {
        user.setRole("ADMIN");
        String old = bearer();
        mvc.perform(get("/api/users/list").header("Authorization", old)).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").header("Authorization", old)).andExpect(status().isOk());
        user.setAuthVersion(1L);
        mvc.perform(get("/api/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
    }
}
