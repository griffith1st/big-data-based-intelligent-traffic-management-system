package com.itms.config;

import com.itms.common.GlobalExceptionHandler;
import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
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
        properties = "itms.concurrency.max-in-flight=1")
@Import({SecurityConfig.class, GlobalExceptionHandler.class, RequestAdmissionTest.Probe.class,
        RequestAdmissionTest.Metrics.class})
class RequestAdmissionTest {
    @Autowired MockMvc mvc;
    @MockBean JwtUtil jwt;
    @MockBean RedisAuthService redisAuth;

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
        var held = executor.submit(() -> mvc.perform(get("/api/auth/hold")).andReturn());
        try {
            assertThat(probe.entered.await(3, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/auth/ping").header("Origin", "http://localhost:3000"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
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
        mvc.perform(get("/api/auth/ping")).andExpect(status().isOk());
    }

    @Test void releasesAdmissionWhenControllerFails() throws Exception {
        mvc.perform(get("/api/auth/fail")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/ping")).andExpect(status().isOk());
    }

    @Test void preservesServiceUnavailableInsteadOfMisclassifyingAsBadRequest() throws Exception {
        mvc.perform(get("/api/auth/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("cache rebuild busy"));
    }

    @Test void databaseTimeoutOrExhaustedPoolIsServerOverloadNotClientError() throws Exception {
        for (String endpoint : new String[]{"/api/auth/db-timeout", "/api/auth/db-connection"}) {
            mvc.perform(get(endpoint)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value(503));
        }
    }
}
