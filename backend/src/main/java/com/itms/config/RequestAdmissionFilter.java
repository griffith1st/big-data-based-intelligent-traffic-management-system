package com.itms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.common.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Per-instance admission for synchronous API work, before JWT/Redis/DB access. */
public class RequestAdmissionFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final ObjectMapper mapper;
    private final Counter rejected;

    public RequestAdmissionFilter(int maxInFlight, ObjectMapper mapper, MeterRegistry metrics) {
        if (maxInFlight < 1) throw new IllegalArgumentException("max-in-flight must be positive");
        this.permits = new Semaphore(maxInFlight);
        this.mapper = mapper;
        this.rejected = metrics.counter("itms.http.admission.rejected");
        Gauge.builder("itms.http.admission.active", permits,
                p -> maxInFlight - p.availablePermits()).register(metrics);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod())
                || !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            rejected.increment();
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), Result.error(429, "请求繁忙，请稍后重试"));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
