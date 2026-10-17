package com.itms.config;

import com.itms.service.RedisAuthService;
import com.itms.util.JwtUtil;
import com.itms.repository.UserRepository;
import com.itms.entity.User;
import java.util.Optional;
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
    UserRepository users;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext(); redis=mock(RedisAuthService.class);
        users=mock(UserRepository.class);
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).username("alice").enabled(true).role("ADMIN").build()));
        jwt=jwt(60_000); filter=new SecurityConfig(jwt,redis,users).jwtAuthenticationFilter();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    MockHttpServletResponse request(String token) throws Exception {
        var req=new MockHttpServletRequest("GET","/api/traffic/realtime");
        if(token!=null) req.addHeader("Authorization","Bearer "+token);
        var response = new MockHttpServletResponse();
        filter.doFilter(req,response,new MockFilterChain());
        return response;
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
    @Test void blacklistOutageReturnsJson503WithoutAuthentication() throws Exception {
        when(redis.isTokenRevoked(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        var response = request(jwt.generateToken(1L,"alice","ADMIN"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("\"code\":503");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
