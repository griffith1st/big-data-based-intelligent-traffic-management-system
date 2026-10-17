package com.itms.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class LoginLockedException extends ResponseStatusException {
    private final long retryAfter;
    public LoginLockedException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请等待锁定时间结束后重试");
        this.retryAfter = Math.max(1, retryAfter);
    }
    @Override public HttpHeaders getHeaders() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        return headers;
    }
}
