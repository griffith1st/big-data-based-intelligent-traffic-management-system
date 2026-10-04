package com.itms.config;

import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    RedisAuthService redis;
    JwtUtil jwt;
    OncePerRequestFilter filter;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext(); redis=mock(RedisAuthService.class);
        jwt=jwt(60_000); filter=new SecurityConfig(jwt,redis).jwtAuthenticationFilter();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void request(String token) throws Exception {
        var req=new MockHttpServletRequest("GET","/api/traffic/realtime");
        if(token!=null) req.addHeader("Authorization","Bearer "+token);
        filter.doFilter(req,new MockHttpServletResponse(),new MockFilterChain());
    }
    @Test void validTokenCreatesAuthenticatedIdentity() throws Exception {
        request(jwt.generateToken(1L,"alice","ADMIN"));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("alice");
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
    }
    @Test void revokedTokenDoesNotAuthenticate() throws Exception {
        String token=jwt.generateToken(1L,"alice","ADMIN"); when(redis.isTokenRevoked(token)).thenReturn(true);
        request(token); assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void expiredTokenNeverReachesRedis() throws Exception {
        request(jwt(-60_000).generateToken(1L,"alice","ADMIN"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); verifyNoInteractions(redis);
    }
    @Test void malformedTokenNeverReachesRedis() throws Exception {
        request("invalid.jwt"); assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); verifyNoInteractions(redis);
    }
    @Test void missingTokenDoesNotAuthenticate() throws Exception {
        request(null); assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); verifyNoInteractions(redis);
    }
    @Test @Tag("known-limitation") void characterizesBlacklistOutagePropagatingFromFilter() {
        when(redis.isTokenRevoked(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        assertThatThrownBy(() -> request(jwt.generateToken(1L,"alice","ADMIN"))).hasMessage("redis unavailable");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
