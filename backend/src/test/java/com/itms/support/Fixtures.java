package com.itms.support;

import com.itms.entity.TrafficData;
import com.itms.util.JwtUtil;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;

public final class Fixtures {
    private Fixtures() {}
    public static JwtUtil jwt(long lifetimeMs) {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-signing-key-not-for-deployment-0123456789abcdef");
        ReflectionTestUtils.setField(jwt, "expiration", lifetimeMs);
        return jwt;
    }
    public static Object[] row(int count, double speed, String direction) {
        return new Object[]{1L, "INT-001", "fixture intersection", 2, 116.39, 39.90,
                count, speed, LocalDateTime.of(2026, 10, 4, 12, 0), direction, "SIMULATOR", 45.0};
    }
    public static TrafficData traffic(int count) {
        return TrafficData.builder().intersectionId("INT-001").vehicleCount(count)
                .avgSpeed(30.0).direction("NORTH").build();
    }
}
