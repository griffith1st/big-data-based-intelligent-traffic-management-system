package com.itms.service.impl;

import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Redis implementation used by the stateless JWT filter and login endpoint.
 * Token values are hashed before becoming Redis keys, so the raw credential is
 * never persisted in Redis. All keys have a TTL and therefore do not become a
 * permanent blacklist.
 */
@Service
@RequiredArgsConstructor
public class RedisAuthServiceImpl implements RedisAuthService {

    private static final String REVOKED_PREFIX = "itms:auth:revoked:";
    private static final String FAILED_PREFIX = "itms:auth:login-failed:";
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final JwtUtil jwtUtil;

    @Override
    public boolean isTokenRevoked(String token) {
        return token != null && Boolean.TRUE.equals(redis.hasKey(revokedKey(token)));
    }

    @Override
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtUtil.parseToken(token);
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redis.opsForValue().set(revokedKey(token), "1", remainingMs, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException ignored) {
            // Invalid/expired JWTs already have no authentication value to revoke.
        }
    }

    @Override
    public long recordLoginFailure(String username) {
        String key = failedKey(username);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, FAILURE_WINDOW);
        }
        return count == null ? 0L : count;
    }

    @Override
    public void clearLoginFailures(String username) {
        redis.delete(failedKey(username));
    }

    private String revokedKey(String token) {
        return REVOKED_PREFIX + sha256(token);
    }

    private String failedKey(String username) {
        return FAILED_PREFIX + sha256(username == null ? "" : username.trim().toLowerCase());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
