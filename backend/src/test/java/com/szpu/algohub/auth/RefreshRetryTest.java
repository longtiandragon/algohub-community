package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.szpu.algohub.common.ApiException;

/** 实际 Redis 轮换：丢失响应可重试，但窗口、浏览器限制和撤销仍生效。 */
@SpringBootTest
class RefreshRetryTest {
    @Autowired TokenStore tokens;
    @Autowired StringRedisTemplate redis;
    private static final long USER = 987654333L;
    private static final String DEVICE = "retry-test-browser";
    private static final String VERSION = "identity-v1";

    @BeforeEach void clear() { redis.keys("algohub:*").forEach(redis::delete); }

    private TokenStore.RefreshToken issue() { return tokens.issueRefreshToken(USER, DEVICE, VERSION); }
    private String retryKey(TokenStore.RefreshToken original) { return "algohub:refresh-retry:" + original.hash(); }

    @Test void droppedResponseRecoversSameEncryptedSuccessorWithoutExtendingWindow() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        long before = redis.getExpire(retryKey(original), TimeUnit.MILLISECONDS);
        var recovered = tokens.rotate(original.value(), DEVICE);

        assertThat(recovered).isEqualTo(next);
        assertThat(tokens.userIdOfRefreshToken(original.value())).isNull();
        assertThat(tokens.refreshIdentity(original.value(), DEVICE))
                .isEqualTo(new TokenStore.RefreshIdentity(USER, VERSION));
        assertThat(tokens.listSessions(USER, next.value())).hasSize(1);
        assertThat(before).isBetween(1L, TokenStore.RETRY_SECONDS * 1000);
        assertThat(redis.getExpire(retryKey(original), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        assertThat(redis.opsForHash().entries(retryKey(original)).toString())
                .doesNotContain(original.value()).doesNotContain(next.value());
    }

    @Test void retryFromAnotherBrowserIsRejected() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        assertThat(tokens.refreshIdentity(original.value(), "another-browser")).isNull();
        assertThatThrownBy(() -> tokens.rotate(original.value(), "another-browser")).isInstanceOf(ApiException.class);
        assertThat(tokens.userIdOfRefreshToken(next.value())).isEqualTo(USER);
    }

    @Test void expiredRetryDoesNotRestoreConsumedToken() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        // 模拟 30 秒窗口自然过期，无须让测试阻塞半分钟。
        redis.expire(retryKey(original), Duration.ZERO);
        assertThat(tokens.refreshIdentity(original.value(), DEVICE)).isNull();
        assertThatThrownBy(() -> tokens.rotate(original.value(), DEVICE)).isInstanceOf(ApiException.class);
        assertThat(tokens.userIdOfRefreshToken(next.value())).isEqualTo(USER);
    }

    @Test void revokedSuccessorCannotBeRestoredFromGraceRecord() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        tokens.revokeRefreshToken(next.value());
        assertThat(tokens.refreshIdentity(original.value(), DEVICE)).isNull();
        assertThatThrownBy(() -> tokens.rotate(original.value(), DEVICE)).isInstanceOf(ApiException.class);
        assertThat(tokens.listSessions(USER, null)).isEmpty();
    }

    @Test void logoutWithOldCookieRevokesCurrentSuccessorThroughShortChain() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        var latest = tokens.rotate(next.value(), DEVICE);
        tokens.revokeRefreshToken(original.value());
        assertThat(tokens.userIdOfRefreshToken(latest.value())).isNull();
        assertThat(tokens.refreshIdentity(original.value(), DEVICE)).isNull();
        assertThat(tokens.refreshIdentity(next.value(), DEVICE)).isNull();
        assertThat(tokens.listSessions(USER, null)).isEmpty();
    }

    @Test void delayedOlderResponseRecoversLatestSuccessorWithoutRenewingOriginalGrace() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        var latest = tokens.rotate(next.value(), DEVICE);
        long before = redis.getExpire(retryKey(original), TimeUnit.MILLISECONDS);
        assertThat(tokens.rotate(original.value(), DEVICE)).isEqualTo(latest);
        assertThat(tokens.rotate(next.value(), DEVICE)).isEqualTo(latest);
        assertThat(tokens.listSessions(USER, latest.value())).hasSize(1);
        assertThat(redis.getExpire(retryKey(original), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        redis.expire(retryKey(original), Duration.ZERO);
        assertThatThrownBy(() -> tokens.rotate(original.value(), DEVICE)).isInstanceOf(ApiException.class);
        assertThat(tokens.rotate(next.value(), DEVICE)).isEqualTo(latest);
    }

    @Test void logoutAllClearsBothActiveSessionAndRetryRecords() {
        var original = issue();
        tokens.rotate(original.value(), DEVICE);
        assertThat(tokens.revokeAllSessions(USER)).isEqualTo(1);
        assertThat(redis.hasKey(retryKey(original))).isFalse();
        assertThat(tokens.refreshIdentity(original.value(), DEVICE)).isNull();
        assertThatThrownBy(() -> tokens.rotate(original.value(), DEVICE)).isInstanceOf(ApiException.class);
    }

    @Test void retryCannotCrossIdentityVersions() {
        var original = issue();
        var next = tokens.rotate(original.value(), DEVICE);
        redis.opsForHash().put("algohub:refresh:" + next.hash(), "identityVersion", "identity-v2");
        assertThat(tokens.refreshIdentity(original.value(), DEVICE)).isNull();
        assertThatThrownBy(() -> tokens.rotate(original.value(), DEVICE)).isInstanceOf(ApiException.class);
    }
}
