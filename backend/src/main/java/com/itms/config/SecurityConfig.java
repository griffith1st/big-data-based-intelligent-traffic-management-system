package com.itms.config;

import com.itms.util.JwtUtil;
import com.itms.service.RedisAuthService;
import com.itms.repository.UserRepository;
import com.itms.security.UserPrincipal;
import com.itms.common.Result;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.CorsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security安全配置
 * JWT无状态认证
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final RedisAuthService redisAuthService;
    private final UserRepository userRepository;
    @Value("${itms.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RequestAdmissionFilter admission) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> writeError(response, 401, "请先登录或重新登录", null))
                .accessDeniedHandler((request, response, error) -> writeError(response, 403, "当前账号没有执行此操作的权限", null)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/users/**", "/api/logs/**", "/actuator/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/traffic/**", "/api/prediction/**", "/api/predictions/**",
                        "/api/signal/**", "/api/signals/**", "/api/alert/**", "/api/alerts/**").hasAnyRole("ADMIN", "TRAFFIC_MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/traffic/**", "/api/prediction/**", "/api/predictions/**",
                        "/api/signal/**", "/api/signals/**", "/api/alert/**", "/api/alerts/**").hasAnyRole("ADMIN", "TRAFFIC_MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/traffic/**", "/api/prediction/**", "/api/predictions/**",
                        "/api/signal/**", "/api/signals/**", "/api/alert/**", "/api/alerts/**").hasAnyRole("ADMIN", "TRAFFIC_MANAGER")
                .anyRequest().hasAnyRole("ADMIN", "TRAFFIC_MANAGER", "DRIVER")
            )
            .addFilterAfter(admission, CorsFilter.class)
            .addFilterBefore(jwtAuthenticationFilter(),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public RequestAdmissionFilter requestAdmissionFilter(
            @Value("${itms.concurrency.max-in-flight:24}") int maxInFlight,
            ObjectMapper mapper, MeterRegistry metrics) {
        return new RequestAdmissionFilter(maxInFlight, mapper, metrics);
    }

    @Bean
    public FilterRegistrationBean<RequestAdmissionFilter> admissionRegistration(RequestAdmissionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Run once inside Security, after CORS.
        return registration;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> jwtRegistration(
            @org.springframework.beans.factory.annotation.Qualifier("jwtAuthenticationFilter") OncePerRequestFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Retry-After"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public OncePerRequestFilter jwtAuthenticationFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain)
                    throws ServletException, IOException {
                String authHeader = request.getHeader("Authorization");

                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    try {
                        var claims = jwtUtil.parseToken(token);
                        var id = claims.get("userId", Long.class);
                        var version = claims.get("authVersion", Number.class);
                        if (id != null && version != null && !redisAuthService.isTokenRevoked(token)) {
                            var user = userRepository.findById(id).orElse(null);
                            if (user != null && Boolean.TRUE.equals(user.getEnabled())
                                    && user.getUsername().equals(claims.getSubject())
                                    && version.longValue() == user.getAuthVersion()
                                    && List.of("ADMIN", "TRAFFIC_MANAGER", "DRIVER").contains(user.getRole())) {
                                var principal = new UserPrincipal(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion());
                                var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            }
                        }
                    } catch (JwtException | IllegalArgumentException ignored) {
                        SecurityContextHolder.clearContext();
                    } catch (RuntimeException failure) {
                        SecurityContextHolder.clearContext();
                        writeError(response, 503, "认证状态服务暂时不可用，请稍后重试", "1");
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private static void writeError(HttpServletResponse response, int code, String message, String retryAfter) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        if (retryAfter != null) response.setHeader("Retry-After", retryAfter);
        ERROR_MAPPER.writeValue(response.getOutputStream(), Result.error(code, message));
    }
}
