package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthDtos.RegisterRequest;
import com.szpu.algohub.auth.AuthDtos.UpdateProfileRequest;
import com.szpu.algohub.auth.identity.IdentityMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

/**
 * 身份与会话的行为验证：令牌签发与轮换、登出失效、改密下线、隐私设置生效。
 * 依赖本机 Redis（docker compose 中的 algohub-redis）。
 */
@SpringBootTest
class AuthFlowTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private IdentityMapper identityMapper;
    @Autowired
    private com.szpu.algohub.statistics.CampusRankingService campusRankingService;
    @Autowired
    private AppProperties properties;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String username;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'auth-test%'");
        redis.keys("algohub:*").forEach(redis::delete);

        username = "auth-test-" + System.nanoTime();
        AppUser user = authService.register(new RegisterRequest(username, "StrongPass123", "认证测试",
                null, null, null, null, null), "127.0.0.1", "JUnit");
        userId = user.getId();
        // Password-based session scenarios now apply only to administrators.
        jdbcTemplate.update("update app_user set role='ADMIN' where id=?", userId);
    }

    @Test
    void loginIssuesParsableAccessTokenAndRefreshToken() {
        var session = authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");

        assertThat(session.view().tokenType()).isEqualTo("Bearer");
        assertThat(session.view().expiresInSeconds()).isPositive();
        var claims = jwtService.parse(session.view().accessToken());
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(userId));
        assertThat(claims.get("username")).isEqualTo(username);
        assertThat(claims.getId()).isNotBlank();
        // 刷新令牌不出现在返回体里，只通过 Cookie 下发
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(session.view().toString()).doesNotContain(session.refreshToken());
        assertThat(tokenStore.userIdOfRefreshToken(session.refreshToken())).isEqualTo(userId);
    }

    @Test
    void wrongPasswordCountsFailuresAndEventuallyLocks() {
        for (int i = 0; i < properties.getSecurity().getMaxLoginFailures(); i++) {
            assertThatThrownBy(() -> authService.login(username, "wrong", "127.0.0.1", "JUnit"))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("用户名或密码错误");
        }
        // 超过阈值后即使密码正确也先被拦住
        assertThatThrownBy(() -> authService.login(username, "StrongPass123", "127.0.0.1", "JUnit"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("登录失败次数过多");

        tokenStore.clearLoginFailures(username);
        assertThat(authService.login(username, "StrongPass123", "127.0.0.1", "JUnit")).isNotNull();
    }

    @Test
    void refreshRotatesAndLostResponseCanRecoverSameSuccessor() {
        var first = authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");
        var second = authService.refresh(first.refreshToken(), "127.0.0.1", "JUnit");

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(tokenStore.userIdOfRefreshToken(first.refreshToken())).isNull();
        var retried = authService.refresh(first.refreshToken(), "127.0.0.1", "JUnit");
        assertThat(retried.refreshToken()).isEqualTo(second.refreshToken());
        assertThat(authService.sessions(userId, second.refreshToken())).hasSize(1);
        assertThatThrownBy(() -> authService.refresh(first.refreshToken(), "127.0.0.1", "another-browser"))
                .isInstanceOf(ApiException.class).hasMessageContaining("刷新令牌无效");
    }

    @Test
    void logoutBlacklistsAccessTokenAndRevokesRefreshToken() {
        var session = authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");
        var claims = jwtService.parse(session.view().accessToken());
        AppUser user = userMapper.findById(userId);
        LoginUser principal = new LoginUser(user, claims.getId(), claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant());

        authService.logout(principal, session.refreshToken(), "127.0.0.1", "JUnit");

        assertThat(tokenStore.isBlacklisted(claims.getId())).isTrue();
        assertThat(tokenStore.userIdOfRefreshToken(session.refreshToken())).isNull();
    }

    @Test
    void changingPasswordInvalidatesEveryIssuedToken() throws InterruptedException {
        var first = authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");
        var second = authService.login(username, "StrongPass123", "127.0.0.1", "JUnit-2");
        Thread.sleep(1100); // tokens_valid_from 以秒级时间比较，确保签发时间确实更早

        authService.changePassword(userId, "StrongPass123", "NewStrongPass456", "127.0.0.1", "JUnit");

        AppUser user = userMapper.findById(userId);
        var claims = jwtService.parse(first.view().accessToken());
        // 令牌签发时间早于 tokens_valid_from，过滤器会据此拒绝
        assertThat(claims.getIssuedAt().toInstant()).isBefore(user.getTokensValidFrom());
        // 所有设备的刷新令牌都被清掉
        assertThat(tokenStore.userIdOfRefreshToken(first.refreshToken())).isNull();
        assertThat(tokenStore.userIdOfRefreshToken(second.refreshToken())).isNull();
        assertThat(authService.sessions(userId, null)).isEmpty();

        assertThat(authService.login(username, "NewStrongPass456", "127.0.0.1", "JUnit")).isNotNull();
    }

    @Test
    void logoutAllRevokesEverySession() {
        authService.login(username, "StrongPass123", "127.0.0.1", "device-1");
        authService.login(username, "StrongPass123", "127.0.0.1", "device-2");
        assertThat(authService.sessions(userId, null)).hasSize(2);

        int revoked = authService.logoutAll(userId, "127.0.0.1", "JUnit");

        assertThat(revoked).isEqualTo(2);
        assertThat(authService.sessions(userId, null)).isEmpty();
    }

    @Test
    void sessionListMarksCurrentDevice() {
        var session = authService.login(username, "StrongPass123", "127.0.0.1", "device-A");
        authService.login(username, "StrongPass123", "127.0.0.1", "device-B");

        List<TokenStore.SessionInfo> sessions = authService.sessions(userId, session.refreshToken());

        assertThat(sessions).hasSize(2);
        assertThat(sessions).filteredOn(TokenStore.SessionInfo::current).hasSize(1);
        assertThat(sessions).extracting(TokenStore.SessionInfo::device).contains("device-A", "device-B");
    }

    @Test
    void newProfilesArePublicButIdentityDetailsStayPrivateAndCanBeUpdated() {
        var privacy = authService.privacy(userId);
        assertThat(privacy.getProfileVisibility()).isEqualTo("PUBLIC");
        assertThat(privacy.isShowStudentNo()).isFalse();
        assertThat(privacy.isShowEmail()).isFalse();
        assertThat(privacy.isShowOnRanking()).isTrue();

        var updated = authService.updatePrivacy(userId,
                new PrivacyRequest("PUBLIC", false, null, null, true), "127.0.0.1", "JUnit");

        assertThat(updated.profileVisibility()).isEqualTo("PUBLIC");
        assertThat(updated.showOnRanking()).isFalse();
        assertThat(updated.showEmail()).isTrue();
        assertThatThrownBy(() -> authService.updatePrivacy(userId,
                new PrivacyRequest("EVERYONE", null, null, null, null), "127.0.0.1", "JUnit"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未知的可见范围");
    }

    @Test
    void optingOutOfRankingRemovesUserFromLeaderboard() {
        jdbcTemplate.update("update app_user set role='STUDENT' where id=?", userId);
        joinDefaultSchool(userId);
        jdbcTemplate.update("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status,
                                              auth_mode, sync_status, supports_history, history_complete,
                                              last_success_at)
                values (?, 'CODEFORCES', ?, 'handle', 'VERIFIED', 'PUBLIC', 'OK', true, true, now())
                """, userId, "uid-" + System.nanoTime());

        assertThat(totalRankingRows()).anyMatch(row -> userId.equals(((Number) row.get("userId")).longValue()));

        authService.updatePrivacy(userId, new PrivacyRequest(null, false, null, null, null),
                "127.0.0.1", "JUnit");

        assertThat(totalRankingRows()).noneMatch(row -> userId.equals(((Number) row.get("userId")).longValue()));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> totalRankingRows() {
        // 以管理员视角看同校榜单，本人是否在榜只取决于隐私设置
        Long viewer = authService.register(new RegisterRequest("auth-test-viewer-" + System.nanoTime(),
                "StrongPass123", "同校同学", null, null, null, null, null), "127.0.0.1", "JUnit").getId();
        joinDefaultSchool(viewer);
        return (java.util.List<java.util.Map<String, Object>>) campusRankingService
                .ranking(viewer, "TOTAL", "FIRST_AC", null, null, null, 50).get("rows");
    }

    private Long joinDefaultSchool(Long user) {
        Long school = jdbcTemplate.queryForObject("select id from school order by id limit 1", Long.class);
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')",
                school, user);
        return school;
    }

    @Test
    void publicProfileRespectsVisibilitySetting() {
        authService.updateProfile(userId, new UpdateProfileRequest("认证测试", "a@example.com", "简介", null),
                "127.0.0.1", "JUnit");
        authService.updatePrivacy(userId, new PrivacyRequest("PRIVATE", null, null, null, null),
                "127.0.0.1", "JUnit");

        Long school = joinDefaultSchool(userId);
        Long classmate = authService.register(new RegisterRequest("auth-test-mate-" + System.nanoTime(),
                "StrongPass123", "同校同学", null, null, null, null, null), "127.0.0.1", "JUnit").getId();
        joinDefaultSchool(classmate);
        Long otherSchool = jdbcTemplate.queryForObject(
                "insert into school (name, invite_code) values ('另一所学校', ?) returning id", Long.class,
                "auth-other-" + System.nanoTime());
        Long outsider = authService.register(new RegisterRequest("auth-test-out-" + System.nanoTime(),
                "StrongPass123", "外校同学", null, null, null, null, null), "127.0.0.1", "JUnit").getId();
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')",
                otherSchool, outsider);
        Long noSchool = authService.register(new RegisterRequest("auth-test-none-" + System.nanoTime(),
                "StrongPass123", "未选校", null, null, null, null, null), "127.0.0.1", "JUnit").getId();

        // 不可见：统一 404，不返回昵称
        assertThatThrownBy(() -> authService.publicProfile(userId, classmate, false))
                .isInstanceOf(com.szpu.algohub.common.ApiException.class)
                .hasMessage(AuthService.PROFILE_NOT_FOUND);

        // 本人与管理员始终可见
        assertThat(authService.publicProfile(userId, userId, false).visible()).isTrue();
        assertThat(authService.publicProfile(userId, outsider, true).visible()).isTrue();

        // 改为仅本校可见后：同校同学可以看到；外校成员与没有学校的人都看不到
        authService.updatePrivacy(userId, new PrivacyRequest("SCHOOL", null, null, null, null),
                "127.0.0.1", "JUnit");
        assertThat(authService.publicProfile(userId, classmate, false).visible()).isTrue();
        assertThatThrownBy(() -> authService.publicProfile(userId, outsider, false)).hasMessage(AuthService.PROFILE_NOT_FOUND);
        assertThatThrownBy(() -> authService.publicProfile(userId, noSchool, false)).hasMessage(AuthService.PROFILE_NOT_FOUND);
        assertThat(school).isNotEqualTo(otherSchool);
    }

    @Test
    void emailIsHiddenUnlessUserOptsIn() {
        authService.updateProfile(userId, new UpdateProfileRequest("认证测试", "secret@example.com", null, null),
                "127.0.0.1", "JUnit");
        authService.updatePrivacy(userId, new PrivacyRequest("PUBLIC", null, null, null, false),
                "127.0.0.1", "JUnit");
        assertThat(authService.publicProfile(userId, 999999L, false).email()).isNull();

        authService.updatePrivacy(userId, new PrivacyRequest("PUBLIC", null, null, null, true),
                "127.0.0.1", "JUnit");
        assertThat(authService.publicProfile(userId, 999999L, false).email())
                .isEqualTo("secret@example.com");
    }

    @Test
    void exportedDataExcludesSecrets() {
        authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");
        Map<String, Object> export = authService.exportMyData(userId);

        assertThat(export).containsKeys("profile", "authEvents", "exportedAt");
        assertThat(export.toString()).doesNotContain("StrongPass123");
        assertThat(export.get("note").toString()).contains("不含密码");
    }

    @Test
    void accountDeletionRequiresPasswordAndUsernameConfirmation() {
        assertThatThrownBy(() -> authService.deleteAccount(userId, "StrongPass123", "wrong-name",
                "127.0.0.1", "JUnit"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请输入正确的用户名");
        assertThatThrownBy(() -> authService.deleteAccount(userId, "wrong-password", username,
                "127.0.0.1", "JUnit"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密码不正确");

        jdbcTemplate.update("update app_user set role='STUDENT' where id=?", userId);
        authService.deleteVerifiedAccount(userId, username, "127.0.0.1", "JUnit");
        assertThat(userMapper.findById(userId)).isNull();
    }

    @Test
    void accountWithRealDataCanStillBeDeleted() {
        // 曾经的缺陷：只要用户有绑定账号、通过记录、日统计、入校申请或建过题单，
        // 删除 app_user 就会被外键挡住，「注销账号」在真实数据下走不通。
        jdbcTemplate.update("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status,
                                              auth_mode, sync_status, supports_history)
                values (?, 'CODEFORCES', ?, 'handle', 'RESOLVED', 'PUBLIC', 'OK', true)
                """, userId, "uid-" + System.nanoTime());
        Long accountId = jdbcTemplate.queryForObject(
                "select id from platform_account where user_id = ?", Long.class, userId);
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_source, tags_complete,
                                     updated_at)
                values ('CODEFORCES', ?, '题目', 'https://example.invalid/x', 'OFFICIAL', true, now())
                """, "DEL" + System.nanoTime());
        Long problemId = jdbcTemplate.queryForObject(
                "select id from problem order by id desc limit 1", Long.class);
        jdbcTemplate.update("""
                insert into user_solved_problem (user_id, account_id, problem_id, platform,
                                                 earliest_known_ac_at, first_ac_confidence, source)
                values (?, ?, ?, 'CODEFORCES', now(), 'EXACT', 'SYNC')
                """, userId, accountId, problemId);
        jdbcTemplate.update("""
                insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
                values (?, ?, 'CODEFORCES', current_date, 'UNIQUE_AC', 1, 'SUBMISSION')
                """, userId, accountId);
        jdbcTemplate.update("""
                insert into problem_list (code, title, source, visible, sort_order, created_by,
                                          owner_id, share_scope)
                values (?, '我的计划', 'MANUAL', true, 100, ?, ?, 'PRIVATE')
                """, "DELLIST" + System.nanoTime(), userId, userId);

        jdbcTemplate.update("update app_user set role='STUDENT' where id=?", userId);
        authService.deleteVerifiedAccount(userId, username, "127.0.0.1", "JUnit");

        assertThat(userMapper.findById(userId)).isNull();
        // 本人数据随账号删除，不留孤儿行
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from platform_account where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from daily_stat where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from problem_list where owner_id = ?", Integer.class, userId)).isZero();
        // 题目本身是公共数据，不能被一个人的注销带走
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from problem where id = ?", Integer.class, problemId)).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void lastAdminCannotDeleteOwnAccount() {
        // 在测试事务内构造唯一管理员，结束时回滚，不依赖测试库的初始账号。
        jdbcTemplate.update("update app_user set role='STUDENT' where role='ADMIN'");
        jdbcTemplate.update("update app_user set role='ADMIN' where id=?", userId);
        assertThat(userMapper.countActiveAdmins()).isEqualTo(1);
        assertThatThrownBy(() -> authService.deleteAccount(userId,
                "StrongPass123", username, "127.0.0.1", "JUnit"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最后一个管理员");
    }

    @Test
    void authEventsRecordLoginAndPrivacyChanges() {
        authService.login(username, "StrongPass123", "127.0.0.1", "JUnit");
        authService.updatePrivacy(userId, new PrivacyRequest("PUBLIC", null, null, null, null),
                "127.0.0.1", "JUnit");

        List<Map<String, Object>> events = identityMapper.recentEvents(userId, 20);
        assertThat(events).extracting(e -> e.get("event"))
                .contains("REGISTER", "LOGIN_SUCCESS", "PRIVACY_UPDATED");
        // 审计里不会出现密码
        assertThat(events.toString()).doesNotContain("StrongPass123");
    }

    @Test
    void invalidTokenIsRejectedAsUnauthenticated() {
        assertThatThrownBy(() -> jwtService.parse("not-a-token"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getKind())
                .isEqualTo(ErrorKind.UNAUTHENTICATED);
    }
}
