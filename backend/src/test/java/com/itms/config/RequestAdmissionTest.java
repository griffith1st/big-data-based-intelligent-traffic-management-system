package com.itms.config;

import com.itms.common.GlobalExceptionHandler;
import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import com.itms.repository.UserRepository;
import com.itms.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RequestAdmissionTest.Probe.class,
        properties = {"itms.concurrency.max-in-flight=1", "itms.cors.allowed-origins=http://localhost:3000"})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, RequestAdmissionTest.Probe.class,
        RequestAdmissionTest.Metrics.class})
class RequestAdmissionTest {
    @Autowired MockMvc mvc;
    @MockBean JwtUtil jwt;
    @MockBean RedisAuthService redisAuth;
    @MockBean UserRepository users;
    @BeforeEach void identity() {
        var fixture = com.itms.support.Fixtures.jwt(60_000);
        when(jwt.parseToken("fixture-token")).thenReturn(fixture.parseToken(fixture.generateToken(1L, "admin", "ADMIN")));
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).username("admin").role("ADMIN").enabled(true).build()));
    }

    @TestConfiguration
    static class Metrics {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @RestController
    static class Probe {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        @GetMapping("/api/auth/hold")
        String hold() throws InterruptedException {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return "done";
        }

        @GetMapping("/api/auth/ping") String ping() { return "pong"; }
        @GetMapping("/api/auth/fail") String fail() { throw new IllegalStateException("expected failure"); }
        @GetMapping("/api/auth/db-timeout") String dbTimeout() {
            throw new org.springframework.dao.QueryTimeoutException("statement timed out");
        }
        @GetMapping("/api/auth/db-connection") String dbConnection() {
            throw new org.springframework.transaction.CannotCreateTransactionException("pool exhausted");
        }
        @GetMapping("/api/auth/busy") String busy() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cache rebuild busy");
        }
    }

    @Autowired Probe probe;

    // Removing admission must let the second request through, failing the 429 assertion.
    @Test void rejectsExcessWorkWithCorsAndRecoversAfterInflightCompletes() throws Exception {
        probe.entered = new CountDownLatch(1);
        probe.release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var held = executor.submit(() -> mvc.perform(get("/api/auth/hold").header("Authorization", "Bearer fixture-token")).andReturn());
        try {
            assertThat(probe.entered.await(3, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/auth/ping").header("Authorization", "Bearer fixture-token").header("Origin", "http://localhost:3000"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"))
                    .andExpect(jsonPath("$.code").value(429));
            mvc.perform(options("/api/auth/ping").header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk());
        } finally {
            probe.release.countDown();
            executor.shutdown();
        }
        assertThat(held.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/auth/ping").header("Authorization", "Bearer fixture-token")).andExpect(status().isOk());
    }

    @Test void releasesAdmissionWhenControllerFails() throws Exception {
        mvc.perform(get("/api/auth/fail").header("Authorization", "Bearer fixture-token")).andExpect(status().isInternalServerError());
        mvc.perform(get("/api/auth/ping").header("Authorization", "Bearer fixture-token")).andExpect(status().isOk());
    }

    @Test void rejectsCorsOriginsOutsideConfiguredList() throws Exception {
        mvc.perform(options("/api/auth/ping").header("Origin", "https://unlisted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void preservesServiceUnavailableInsteadOfMisclassifyingAsBadRequest() throws Exception {
        mvc.perform(get("/api/auth/busy").header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("cache rebuild busy"));
    }

    @Test void databaseTimeoutOrExhaustedPoolIsServerOverloadNotClientError() throws Exception {
        for (String endpoint : new String[]{"/api/auth/db-timeout", "/api/auth/db-connection"}) {
            mvc.perform(get(endpoint).header("Authorization", "Bearer fixture-token")).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value(503));
        }
    }
}
