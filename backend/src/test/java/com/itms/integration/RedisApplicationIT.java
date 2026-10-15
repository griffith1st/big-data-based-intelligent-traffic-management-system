package com.itms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.config.SecurityConfig;
import com.itms.dto.LoginRequest;
import com.itms.entity.User;
import com.itms.repository.*;
import com.itms.service.*;
import com.itms.service.impl.*;
import com.itms.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.itms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Real Redis + real application services/filter/JSON. JPA and alert side effects are mocked.
 * Run only via scripts/test-redis.ps1 against its disposable, loopback-only container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisApplicationIT {
    static final String REALTIME="itms:traffic:realtime";
    static final String SUMMARY="itms:traffic:intersection-summary";
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    JwtUtil jwt;
    RedisAuthServiceImpl authState;
    TrafficDataRepository db;
    TrafficAlertService alerts;
    TrafficDataServiceImpl traffic;

    @BeforeAll void connect() {
        assertThat(System.getProperty("test.redis.isolated")).as("Use scripts/test-redis.ps1 to create an isolated Redis").isEqualTo("true");
        int port=Integer.parseInt(System.getProperty("test.redis.port"));
        factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port));
        factory.afterPropertiesSet(); factory.start();
        redis=new StringRedisTemplate(factory);
        try(var connection=factory.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
    }
    @BeforeEach void setup() {
        // Only this run's disposable Redis; no application Redis is contacted.
        try(var connection=factory.getConnection()) { connection.serverCommands().flushDb(); }
        SecurityContextHolder.clearContext(); jwt=jwt(60_000);
        authState=new RedisAuthServiceImpl(redis,jwt);
        db=mock(TrafficDataRepository.class); alerts=mock(TrafficAlertService.class);
        traffic=new TrafficDataServiceImpl(db,alerts,redis,new ObjectMapper().findAndRegisterModules());
        when(db.count()).thenReturn(1L);
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(600,25,"NORTH")));
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    @AfterAll void disconnect() { if(factory!=null) factory.destroy(); }
    static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    boolean authenticates(String token) throws Exception {
        SecurityContextHolder.clearContext();
        var request=new MockHttpServletRequest("GET","/api/traffic/realtime");
        request.addHeader("Authorization","Bearer "+token);
        new SecurityConfig(jwt,authState).jwtAuthenticationFilter().doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication()!=null;
    }
    // Missing logout->Redis wiring or filter revocation lookup breaks this behavior.
    @Test void logoutRevokesOnlyThatTokenAndUsesRemainingLifetime() throws Exception {
        String token=jwt.generateToken(1L,"alice","ADMIN");
        String other=jwt.generateToken(2L,"bob","ADMIN");
        assertThat(authenticates(token)).isTrue();
        new AuthServiceImpl(mock(UserRepository.class),jwt,mock(SystemLogService.class),authState).logout("Bearer "+token);
        String key="itms:auth:revoked:"+digest(token);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        long remaining=jwt.parseToken(token).getExpiration().getTime()-System.currentTimeMillis();
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(Math.max(1,remaining-2000),remaining+2000);
        assertThat(redis.keys("itms:auth:revoked:*")).containsExactly(key);
        assertThat(authenticates(token)).isFalse(); assertThat(authenticates(other)).isTrue();
    }
    @Test void revokedEntryDisappearsWhenTokenLifetimeEnds() {
        var shortJwt=jwt(4_000); String token=shortJwt.generateToken(1L,"short","ADMIN");
        var state=new RedisAuthServiceImpl(redis,shortJwt); state.revokeToken(token);
        assertThat(state.isTokenRevoked(token)).isTrue();
        await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> assertThat(state.isTokenRevoked(token)).isFalse());
        assertThat(shortJwt.validateToken(token)).isFalse();
    }
    @Test void failureWindowDoesNotRefreshAndSuccessfulClearResetsCounter() throws Exception {
        assertThat(authState.recordLoginFailure(" Alice ")).isEqualTo(1);
        String key="itms:auth:login-failed:"+digest("alice");
        assertThat(redis.getExpire(key,TimeUnit.SECONDS)).isBetween(590L,600L);
        // Bring the first window near its end without a 10 minute sleep.
        redis.expire(key,Duration.ofSeconds(30));
        assertThat(authState.recordLoginFailure("alice")).isEqualTo(2);
        assertThat(redis.getExpire(key,TimeUnit.SECONDS)).isBetween(1L,30L);
        assertThat(authState.recordLoginFailure("bob")).isEqualTo(1);
        authState.clearLoginFailures("ALICE");
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(authState.recordLoginFailure("alice")).isEqualTo(1);
    }
    @Test @Tag("known-limitation") void fiveWrongPasswordsStillAllowCorrectPasswordAndClearCounter() throws Exception {
        var users=mock(UserRepository.class);
        var user=User.builder().id(1L).username("alice").password("fixture-password").enabled(true).role("ADMIN").build();
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        var login=new AuthServiceImpl(users,jwt,mock(SystemLogService.class),authState);
        var request=new LoginRequest(); request.setUsername("alice"); request.setPassword("wrong");
        for(int i=0;i<5;i++) assertThatThrownBy(() -> login.login(request)).isInstanceOf(RuntimeException.class);
        String key="itms:auth:login-failed:"+digest("alice");
        assertThat(redis.opsForValue().get(key)).isEqualTo("5");
        request.setPassword("fixture-password"); var response=login.login(request);
        assertThat(jwt.validateToken(response.getToken())).isTrue();
        assertThat(redis.hasKey(key)).isFalse(); assertThat(user.getLastLoginTime()).isNotNull();
    }
    @Test void invalidAndDisabledUsersDoNotReceiveTokens() {
        var users=mock(UserRepository.class);
        when(users.findByUsername("disabled")).thenReturn(Optional.of(User.builder().username("disabled").enabled(false).password("pass").build()));
        var login=new AuthServiceImpl(users,jwt,mock(SystemLogService.class),authState);
        var request=new LoginRequest(); request.setPassword("pass"); request.setUsername("missing");
        assertThatThrownBy(() -> login.login(request)).isInstanceOf(RuntimeException.class);
        request.setUsername("disabled"); assertThatThrownBy(() -> login.login(request)).isInstanceOf(RuntimeException.class);
        verify(users,never()).save(any());
    }
    @Test void coldQueryPopulatesCacheAndHotQueryAvoidsDatabase() throws Exception {
        assertThat(traffic.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(600);
        assertThat(redis.getExpire(REALTIME,TimeUnit.MILLISECONDS)).isBetween(1L,5000L);
        assertThat(new ObjectMapper().readTree(redis.opsForValue().get(REALTIME)).get(0).get("vehicleCount").asInt()).isEqualTo(600);
        assertThat(traffic.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(600);
        verify(db,times(1)).findRealTimeCongestion();
    }
    @Test void expiredTrafficCacheReloadsChangedDatabaseValue() {
        traffic.getRealTimeData();
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(900,20,"NORTH")));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(redis.hasKey(REALTIME)).isFalse());
        assertThat(traffic.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900);
        verify(db,times(2)).findRealTimeCongestion();
    }
    @Test void summaryUsesRealtimeCacheAndSuccessfulWriteInvalidatesBoth() {
        traffic.getRealTimeData();
        assertThat(traffic.getIntersectionSummary()).singleElement().satisfies(r -> assertThat(r.get("totalVehicleCount")).isEqualTo(600));
        assertThat(redis.getExpire(SUMMARY,TimeUnit.MILLISECONDS)).isBetween(1L,5000L);
        traffic.getIntersectionSummary(); verify(db,times(1)).findRealTimeCongestion();
        var data=traffic(900); when(db.save(data)).thenReturn(data);
        traffic.saveTrafficData(data);
        assertThat(redis.hasKey(REALTIME)).isFalse(); assertThat(redis.hasKey(SUMMARY)).isFalse();
        when(db.findRealTimeCongestion()).thenReturn(List.<Object[]>of(row(900,20,"NORTH")));
        assertThat(traffic.getIntersectionSummary().get(0).get("totalVehicleCount")).isEqualTo(900);
    }
    @Test void oldReadDoesNotRefillStaleValueAfterWriterInvalidates() throws Exception {
        var readStarted=new CountDownLatch(1); var resumeRead=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        when(db.findRealTimeCongestion()).thenAnswer(inv -> {
            readStarted.countDown();
            if(!resumeRead.await(5,TimeUnit.SECONDS)) throw new AssertionError("reader latch timed out");
            return List.<Object[]>of(row(600,25,"NORTH"));
        });
        Future<List<Map<String,Object>>> reader=executor.submit(traffic::getRealTimeData);
        try {
            assertThat(readStarted.await(5,TimeUnit.SECONDS)).isTrue();
            var data=traffic(900); when(db.save(data)).thenReturn(data);
            traffic.saveTrafficData(data); assertThat(redis.hasKey(REALTIME)).isFalse();
            // doReturn avoids executing the previous blocking Answer while restubbing.
            doReturn(List.<Object[]>of(row(900,20,"NORTH"))).when(db).findRealTimeCongestion();
            resumeRead.countDown(); assertThat(reader.get(5,TimeUnit.SECONDS).get(0).get("vehicleCount")).isEqualTo(600);
            // A reader which started before the save may finish with its old snapshot,
            // but that snapshot must not become the next caller's cache value.
            assertThat(traffic.getRealTimeData().get(0).get("vehicleCount")).isEqualTo(900);
        } finally {
            resumeRead.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void concurrentFailuresKeepEveryIncrementAndAWindowTtl() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 80; i++) tasks.add(() -> authState.recordLoginFailure("parallel"));
            var counts = new HashSet<Long>();
            for (var future : pool.invokeAll(tasks)) counts.add(future.get(5, TimeUnit.SECONDS));
            assertThat(counts).hasSize(80).contains(1L, 80L);
            String key = "itms:auth:login-failed:" + digest("parallel");
            assertThat(redis.opsForValue().get(key)).isEqualTo("80");
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(580L, 600L);
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void oldCounterWithoutExpirationGetsAWindowOnNextFailure() throws Exception {
        String key = "itms:auth:login-failed:" + digest("legacy");
        redis.opsForValue().set(key, "4");
        assertThat(authState.recordLoginFailure("legacy")).isEqualTo(5L);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(590L, 600L);
    }
}
