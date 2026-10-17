package com.itms.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorContractTest {
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @RestController
    static class Probe {
        record Input(@NotBlank String name) {}
        @PostMapping("/probe/input") String input(@Valid @RequestBody Input input) { return input.name(); }
        @GetMapping("/probe/number") int number(@RequestParam int count) { return count; }
        @GetMapping("/probe/conflict") void conflict() {
            throw new DataIntegrityViolationException("SQL unique users.email private@example.invalid");
        }
        @GetMapping("/probe/crash") void crash() {
            throw new IllegalStateException("internal SQL credentials should not appear");
        }
        @GetMapping("/probe/locked") void locked() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后重试") {
                @Override public HttpHeaders getHeaders() {
                    var headers = new HttpHeaders();
                    headers.set("Retry-After", "37");
                    return headers;
                }
            };
        }
    }

    @Test void invalidInputIsClientErrorWithNoStackOrDatabaseDetail() throws Exception {
        mvc.perform(post("/probe/input").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void malformedJsonIsClientError() throws Exception {
        mvc.perform(post("/probe/input").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void wrongOrMissingQueryParameterIsClientError() throws Exception {
        mvc.perform(get("/probe/number").param("count", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/probe/number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void uniqueConstraintFailureIsConflictWithoutLeakingSql() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("数据冲突，请检查重复记录或当前状态"));
    }

    @Test void unexpectedFailureIsServerErrorWithoutLeakingInternals() throws Exception {
        mvc.perform(get("/probe/crash"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统内部错误"));
    }

    @Test void lockoutPreservesItsActualRetryWindow() throws Exception {
        mvc.perform(get("/probe/locked"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429))
                .andExpect(header().string("Retry-After", "37"));
    }

    @Test void unsupportedHttpMethodRetainsItsProtocolStatus() throws Exception {
        mvc.perform(delete("/probe/input"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(405));
    }
}
