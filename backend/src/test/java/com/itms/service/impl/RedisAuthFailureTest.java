package com.itms.service.impl;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisAuthFailureTest {
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    RedisAuthServiceImpl service;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values); service=new RedisAuthServiceImpl(redis,jwt(60_000));
    }
    @Test void invalidOrExpiredTokenDoesNotCreateRevocationEntry() {
        service.revokeToken(null); service.revokeToken(" "); service.revokeToken("not-a-token");
        service.revokeToken(jwt(-60_000).generateToken(1L,"alice","ADMIN"));
        verify(values,never()).set(anyString(),anyString(),anyLong(),any(TimeUnit.class));
    }
    @Test @Tag("known-limitation") void characterizesLogoutSwallowingRedisWriteFailure() {
        doThrow(new IllegalStateException("write unavailable")).when(values).set(anyString(),anyString(),anyLong(),any(TimeUnit.class));
        String token=jwt(60_000).generateToken(1L,"alice","ADMIN");
        assertThatCode(() -> service.revokeToken(token)).doesNotThrowAnyException();
        verify(values).set(anyString(),eq("1"),anyLong(),eq(TimeUnit.MILLISECONDS));
    }
    @Test @Tag("known-limitation") void characterizesFailureBetweenIncrementAndExpire() {
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(),any(Duration.class))).thenThrow(new IllegalStateException("expire failed after increment"));
        assertThatThrownBy(() -> service.recordLoginFailure("alice")).hasMessage("expire failed after increment");
        verify(values).increment(anyString());
    }
}
