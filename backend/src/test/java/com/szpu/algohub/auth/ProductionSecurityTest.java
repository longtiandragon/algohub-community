package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"algohub.security.secure-cookie=true", "algohub.security.rate-limit-scale=1"})
@AutoConfigureMockMvc
class ProductionSecurityTest {
    @Autowired TokenStore tokens;
    @Autowired StringRedisTemplate redis;
    @Autowired RefreshCookies cookies;
    @Autowired AuthRateLimit limits;
    @Autowired MockMvc mvc;
    @BeforeEach void clear() { redis.keys("algohub:*").forEach(redis::delete); }

    @Test void concurrentRefreshSharesExactlyOneSuccessor() throws Exception {
        var original = tokens.issueRefreshToken(987654321L, "test");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(24)) {
            List<Future<TokenStore.RefreshToken>> jobs = new ArrayList<>();
            for (int i=0;i<24;i++) jobs.add(pool.submit(() -> {
                gate.await();
                try { return tokens.rotate(original.value(), "parallel"); }
                catch (com.szpu.algohub.common.ApiException e) { return null; }
            }));
            gate.countDown();
            List<TokenStore.RefreshToken> winners = new ArrayList<>();
            for (var job : jobs) { var value = job.get(10, TimeUnit.SECONDS); if (value != null) winners.add(value); }
            assertThat(winners).hasSize(24);
            assertThat(winners).extracting(TokenStore.RefreshToken::value).containsOnly(winners.getFirst().value());
            assertThat(tokens.userIdOfRefreshToken(original.value())).isNull();
            assertThat(tokens.userIdOfRefreshToken(winners.getFirst().value())).isEqualTo(987654321L);
            assertThat(tokens.listSessions(987654321L, winners.getFirst().value())).hasSize(1);
            tokens.revokeAllSessions(987654321L);
            assertThatThrownBy(() -> tokens.rotate(winners.getFirst().value(), "revoked"))
                    .isInstanceOf(com.szpu.algohub.common.ApiException.class);
        }
    }
    @Test void oauthStateIsSingleUseUnderConcurrency() throws Exception {
        String state = tokens.createOauthState(42L, "GITHUB");
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i=0;i<16;i++) calls.add(() -> tokens.consumeOauthState(state, "GITHUB"));
            int successes = 0;
            for (var future : pool.invokeAll(calls)) if (future.get() != null) successes++;
            assertThat(successes).isEqualTo(1);
        }
    }
    @Test void concurrentIssuanceLeavesNoRefreshTokensOutsideSessionIndex() throws Exception {
        long id=987654320L;
        try(var pool=Executors.newFixedThreadPool(16)) {
            List<Callable<TokenStore.RefreshToken>> calls=new ArrayList<>();
            for(int i=0;i<48;i++)calls.add(()->tokens.issueRefreshToken(id,"parallel","identity-v1"));
            var futures=pool.invokeAll(calls);
            var indexed=redis.opsForZSet().range("algohub:sessions:"+id,0,-1);
            for(var future:futures){var token=future.get();
                assertThat(tokens.userIdOfRefreshToken(token.value())!=null).isEqualTo(indexed.contains(token.hash()));}
            tokens.revokeAllSessions(id);
            for(var future:futures)assertThat(tokens.userIdOfRefreshToken(future.get().value())).isNull();
        }
    }
    @Test void secureCookieAttributesMatchWhenCleared() {
        var issued = cookies.create("test", java.time.Duration.ofDays(1));
        var cleared = cookies.clear();
        assertThat(issued.isSecure()).isTrue();
        assertThat(cleared.isSecure()).isTrue();
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.getPath()).isEqualTo(issued.getPath());
        assertThat(cleared.getSameSite()).isEqualTo("Strict");
        assertThat(cleared.getMaxAge()).isZero();
    }
    @Test void arbitraryUsernamesCannotBypassIpLimitAndForwardedHeaderCannotSpoofIt() throws Exception {
        for(int i=0;i<120;i++) limits.check("auth:ip", "127.0.0.1",120,60);
        var response = mvc.perform(post("/api/auth/login").header("X-Forwarded-For","203.0.113.123")
                .contentType("application/json").content("{\"username\":\"another-user\",\"password\":\"some-password\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
    }
    @Test void limitsHaveExpiryAndGlobalMailLimitAppliesAcrossIps() throws Exception {
        for (int i=0;i<100;i++) limits.check("mail:global","all",100,60);
        assertThat(redis.keys("algohub:rate:mail:global:*")).allSatisfy(key ->
                assertThat(redis.getExpire(key)).isBetween(1L,60L));
        var response = mvc.perform(post("/api/auth/email/code").with(r->{r.setRemoteAddr("203.0.113.10");return r;})
                .contentType("application/json").content("{\"email\":\"test@example.com\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
    }
}
