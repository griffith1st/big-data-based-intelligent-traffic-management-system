package com.itms.service;

/**
 * Redis-backed authentication controls: JWT revocation and login-failure limits.
 */
public interface RedisAuthService {

    boolean isTokenRevoked(String token);

    void revokeToken(String token);

    /** Returns the current failed-attempt count for a username. */
    long recordLoginFailure(String username);

    void clearLoginFailures(String username);
}
