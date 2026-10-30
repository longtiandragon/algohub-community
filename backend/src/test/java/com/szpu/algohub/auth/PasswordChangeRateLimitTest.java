package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordChangeRateLimitTest {
    private static final String ORIGINAL = "password-change-original";
    private static final String REPLACEMENT = "password-change-replacement";
    private final String ip = "password-change-test-" + UUID.randomUUID();
    private final List<Long> ids = new ArrayList<>();

    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired AuthService auth;
    @Autowired AuthRateLimit limiter;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;

    private AppUser admin() {
        long id = db.queryForObject("""
                insert into app_user(username,display_name,role,password_hash)
                values(?,'password change limit','ADMIN',?) returning id
                """, Long.class, "password-limit-" + UUID.randomUUID(), encoder.encode(ORIGINAL));
        ids.add(id);
        return users.findById(id);
    }

    @AfterEach
    void clearOwnLimitKeys() {
        for (long id : ids) {
            redis.delete("algohub:rate:password-change:user:" + EmailLoginService.hash(Long.toString(id)));
        }
        redis.delete("algohub:rate:password-change:ip:" + EmailLoginService.hash(ip));
    }

    @Test
    void eleventhAttemptIsRejectedEvenWithCorrectOldPassword() throws Exception {
        var admin = admin();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/me/password").with(user(new LoginUser(admin)))
                    .with(request -> { request.setRemoteAddr(ip); return request; })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"oldPassword\":\"wrong-password\",\"newPassword\":\"" + REPLACEMENT + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/me/password").with(user(new LoginUser(admin)))
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"" + ORIGINAL + "\",\"newPassword\":\"" + REPLACEMENT + "\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(encoder.matches(ORIGINAL, users.findById(admin.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void exhaustedUserBudgetDoesNotPreventAnotherUserOnTheSameIpChangingPassword() {
        var exhausted = admin();
        var other = admin();
        for (int i = 0; i < 10; i++) {
            limiter.check("password-change:user", exhausted.getId().toString(), 10, 300);
        }
        assertThatThrownBy(() -> auth.changePassword(exhausted.getId(), ORIGINAL, REPLACEMENT, ip, "JUnit"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED));

        auth.changePassword(other.getId(), ORIGINAL, REPLACEMENT, ip, "JUnit");

        assertThat(encoder.matches(REPLACEMENT, users.findById(other.getId()).getPasswordHash())).isTrue();
        assertThat(encoder.matches(ORIGINAL, users.findById(exhausted.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void ipBudgetAlsoAppliesBeforePasswordChanges() {
        var admin = admin();
        for (int i = 0; i < 120; i++) limiter.check("password-change:ip", ip, 120, 60);

        assertThatThrownBy(() -> auth.changePassword(admin.getId(), ORIGINAL, REPLACEMENT, ip, "JUnit"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED));

        assertThat(encoder.matches(ORIGINAL, users.findById(admin.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void rejectsOversizedOldPasswordBeforeItUsesTheAttemptBudget() throws Exception {
        var admin = admin();
        mvc.perform(post("/api/me/password").with(user(new LoginUser(admin)))
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"" + "x".repeat(65) + "\",\"newPassword\":\"" + REPLACEMENT + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(redis.hasKey("algohub:rate:password-change:user:" + EmailLoginService.hash(admin.getId().toString())))
                .isFalse();
    }
}
