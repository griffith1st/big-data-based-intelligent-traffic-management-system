package com.itms.service.impl;

import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import com.itms.security.LoginLockedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
    private static final DefaultRedisScript<Long> INCREMENT_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 5 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> LOCK_REMAINING = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count < 5 then return 0 end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return math.max(1, math.ceil(ttl / 1000))
            """, Long.class);
    private static final DefaultRedisScript<Long> CLEAR_UNLOCKED_FAILURES = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= 5 then
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    ttl = tonumber(ARGV[1])
                end
                return math.max(1, math.ceil(ttl / 1000))
            end
            redis.call('DEL', KEYS[1])
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JwtUtil jwtUtil;

    @Override
    public boolean isTokenRevoked(String token) {
        try {
            return token != null && Boolean.TRUE.equals(redis.hasKey(revokedKey(token)));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException | IllegalArgumentException ignored) {
            return; // Already-invalid tokens have no authentication value.
        }
        try {
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redis.opsForValue().set(revokedKey(token), "1", remainingMs, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public long recordLoginFailure(String username) {
        String key = failedKey(username);
        // Counter creation and expiration are one Redis operation. Also repairs
        // legacy counters left without a TTL by the previous INCR/EXPIRE pair.
        Long count = redis.execute(INCREMENT_FAILURE, List.of(key), Long.toString(FAILURE_WINDOW.toMillis()));
        if (count == null) {
            throw new IllegalStateException("Redis did not return a login failure count");
        }
        return count;
    }

    @Override
    public long loginRetryAfterSeconds(String username) {
        try {
            Long ttl = redis.execute(LOCK_REMAINING, List.of(failedKey(username)), Long.toString(FAILURE_WINDOW.toMillis()));
            if (ttl == null) throw new IllegalStateException("Redis returned no lock state");
            return ttl;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void clearLoginFailures(String username) {
        Long retry = redis.execute(CLEAR_UNLOCKED_FAILURES, List.of(failedKey(username)), Long.toString(FAILURE_WINDOW.toMillis()));
        if (retry == null) throw new IllegalStateException("Redis returned no lock state");
        if (retry > 0) throw new LoginLockedException(retry);
    }

    private ResponseStatusException unavailable(RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证状态服务暂时不可用，请稍后重试", cause);
    }

    private String revokedKey(String token) {
        return REVOKED_PREFIX + sha256(token);
    }

    private String failedKey(String username) {
        return FAILED_PREFIX + sha256(username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
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
