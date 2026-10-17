package com.itms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.dto.UserUpdateRequest;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.security.Passwords;
import com.itms.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions/locks + real HTTP security filters; owns only itms_auth_it and Redis database 3. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "itms.demo.enabled=false", "itms.traffic.simulator.enabled=false", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.username=postgres", "spring.datasource.password=", "spring.data.redis.database=3"
})
@AutoConfigureMockMvc
class UserManagementPostgresIT {
    @DynamicPropertySource static void services(DynamicPropertyRegistry registry) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        int pg = Integer.parseInt(System.getProperty("test.postgres.port"));
        int redis = Integer.parseInt(System.getProperty("test.redis.port"));
        assertThat(pg).isBetween(1024, 65535); assertThat(redis).isBetween(1024, 65535);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + pg + "/itms_auth_it");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redis);
    }
    @Autowired UserRepository users;
    @Autowired UserManagementService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @BeforeEach void clearOwnedDatabase() {
        jdbc.execute("TRUNCATE TABLE system_logs, users RESTART IDENTITY");
        var keys = redis.keys("itms:auth:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    User account(String name, String role) {
        return users.saveAndFlush(User.builder().username(name).password(Passwords.encode("fixture-password"))
                .role(role).enabled(true).realName(name).build());
    }
    String login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    @Test void simultaneousRemovalOfTwoAdminsKeepsOneEnabledAdmin() throws Exception {
        var first = account("first-admin", "ADMIN"); var second = account("second-admin", "ADMIN");
        var start = new CountDownLatch(1); var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> removeFirst = () -> removeAfterBarrier(first.getId(), ready, start);
            Callable<Integer> removeSecond = () -> removeAfterBarrier(second.getId(), ready, start);
            var one = pool.submit(removeFirst); var two = pool.submit(removeSecond);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(users.countByRoleAndEnabledTrue("ADMIN")).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    int removeAfterBarrier(Long id, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
        try { service.delete(id); return 200; }
        catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
    }

    @Test void lastAdminCannotBeDisabledOrDemoted() {
        var admin = account("last-admin", "ADMIN");
        var request = new UserUpdateRequest(); request.setRole("ADMIN"); request.setEnabled(false);
        assertThatThrownBy(() -> service.update(admin.getId(), request)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(409));
        request.setEnabled(true); request.setRole("DRIVER");
        assertThatThrownBy(() -> service.update(admin.getId(), request)).isInstanceOf(ResponseStatusException.class);
        assertThat(users.countByRoleAndEnabledTrue("ADMIN")).isEqualTo(1);
    }

    @Test void accountLifecycleEnforcesRolesPasswordRevocationAndLogout() throws Exception {
        var admin = account("admin-test", "ADMIN");
        String adminToken = login("admin-test", "fixture-password");
        mvc.perform(get("/api/auth/me").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(admin.getId()))
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.token").doesNotExist());
        var created = mvc.perform(post("/api/users").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-driver\",\"password\":\"fixture-password\",\"role\":\"DRIVER\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        long driverId = json.readTree(created.getContentAsString()).path("data").path("id").asLong();
        String driverToken = login("new-driver", "fixture-password");
        mvc.perform(get("/api/users/list").header("Authorization", driverToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/traffic/save").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/" + driverId).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-driver\",\"password\":\"changed-password\",\"role\":\"DRIVER\",\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", driverToken)).andExpect(status().isUnauthorized());
        String fresh = login("new-driver", "changed-password");
        mvc.perform(get("/api/auth/me").header("Authorization", fresh)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").header("Authorization", fresh)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", fresh)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_logs WHERE username='admin-test' AND module='用户管理'", Long.class))
                .isEqualTo(2);
    }

    @Test void staleLoginTimestampUpdateCannotRestoreDisabledAccountOrOldPassword() {
        var user = account("stale-login", "DRIVER");
        var staleVersion = user.getAuthVersion();
        var request = new UserUpdateRequest(); request.setRole("DRIVER"); request.setEnabled(false);
        request.setPassword("changed-password"); service.update(user.getId(), request);
        int updated = users.recordLogin(user.getId(), staleVersion, java.time.LocalDateTime.now());
        assertThat(updated).isZero();
        var current = users.findById(user.getId()).orElseThrow();
        assertThat(current.getEnabled()).isFalse();
        assertThat(Passwords.matches("changed-password", current.getPassword())).isTrue();
        assertThat(current.getAuthVersion()).isEqualTo(1);
    }
}
