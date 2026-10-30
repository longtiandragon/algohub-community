package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.szpu.algohub.account.AvatarService.AvatarOption;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.sync.SyncService;

/**
 * 头像只能从本人已验证、有头像的 OJ 账号里选；账号解绑、头像变回默认图、账号转给别人时用户头像跟着变回字标；
 * 同步后刷新头像至少隔 24 小时。
 */
@SpringBootTest
class AvatarServiceTest {

    private static final String CF_URL = "https://userpic.codeforces.org/1/title/0123456789abcdef.jpg";

    @Autowired
    private AvatarService avatars;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PlatformRegistry registry;
    @MockitoBean
    private SyncService sync;

    private PlatformAdapter withAvatar;
    private PlatformAdapter withoutAvatar;
    private Long user;
    private Long other;

    @BeforeEach
    void setUp() {
        user = newUser();
        other = newUser();
        withAvatar = mock(PlatformAdapter.class);
        withoutAvatar = mock(PlatformAdapter.class);
        when(withAvatar.avatarSupported()).thenReturn(true);
        when(withAvatar.lookupKey(any(), any())).thenCallRealMethod();
        when(withAvatar.avatarOf(any())).thenCallRealMethod();
        when(registry.find(any())).thenReturn(Optional.of(withAvatar));
        when(registry.find(Platform.HDU)).thenReturn(Optional.of(withoutAvatar));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from platform_account where user_id in (?,?)", user, other);
        jdbc.update("delete from app_user where id in (?,?)", user, other);
    }

    private Long newUser() {
        return jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name) values (?, 'x', 'avatar') returning id
                """, Long.class, "avatar-" + UUID.randomUUID());
    }

    /** checkedHoursAgo 为空 = 还没查过头像。 */
    private Long account(Long owner, String platform, String ownership, String avatarUrl, Integer checkedHoursAgo) {
        return jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, auth_mode,
                                              sync_status, avatar_url, avatar_checked_at)
                values (?, ?, ?, ?, ?, 'PUBLIC', 'IDLE', ?,
                        case when ?::int is null then null else now() - (?::int * interval '1 hour') end)
                returning id
                """, Long.class, owner, platform, "uid-" + UUID.randomUUID(), "h-" + UUID.randomUUID(), ownership,
                avatarUrl, checkedHoursAgo, checkedHoursAgo);
    }

    private String userAvatar(Long id) {
        return jdbc.queryForObject("select avatar_url from app_user where id = ?", String.class, id);
    }

    private Long userSource(Long id) {
        return jdbc.queryForObject("select avatar_account_id from app_user where id = ?", Long.class, id);
    }

    private AvatarOption option(Long accountId) {
        return avatars.view(user).options().stream().filter(o -> o.accountId().equals(accountId)).findFirst()
                .orElseThrow();
    }

    @Test
    void onlyOwnVerifiedAccountsWithARealAvatarCanBeChosen() {
        Long codeforces = account(user, "CODEFORCES", "VERIFIED", CF_URL, 1);
        Long defaultAvatar = account(user, "LUOGU", "VERIFIED", null, 1); // 查过、平台没给头像地址
        Long unchecked = account(user, "ATCODER", "VERIFIED", null, null);
        Long noAvatars = account(user, "HDU", "VERIFIED", null, null);
        Long unverified = account(user, "LEETCODE_CN", "RESOLVED", "https://assets.leetcode.cn/x.png", 1);
        Long someoneElse = account(other, "CODEFORCES", "VERIFIED", "https://userpic.codeforces.org/2/x.jpg", 1);

        assertThat(option(codeforces).selectable()).isTrue();
        assertThat(option(codeforces).avatarUrl()).isEqualTo(CF_URL);
        assertThat(option(defaultAvatar).reason()).isEqualTo(AvatarService.REASON_NONE);
        assertThat(option(unchecked).reason()).isEqualTo(AvatarService.REASON_UNCHECKED);
        assertThat(option(noAvatars).reason()).isEqualTo(AvatarService.REASON_UNSUPPORTED);
        assertThat(option(unverified).reason()).isEqualTo(AvatarService.REASON_UNVERIFIED);
        assertThat(avatars.view(user).options()).extracting(AvatarOption::accountId).doesNotContain(someoneElse);

        assertThatThrownBy(() -> avatars.select(user, defaultAvatar)).hasMessage(AvatarService.REASON_NONE);
        assertThatThrownBy(() -> avatars.select(user, noAvatars)).hasMessage(AvatarService.REASON_UNSUPPORTED);
        assertThatThrownBy(() -> avatars.select(user, unverified)).hasMessage(AvatarService.REASON_UNVERIFIED);
        assertThatThrownBy(() -> avatars.select(user, someoneElse))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.NOT_FOUND));
        assertThat(userSource(user)).isNull();

        var chosen = avatars.select(user, codeforces);
        assertThat(chosen.selectedAccountId()).isEqualTo(codeforces);
        assertThat(chosen.avatarUrl()).isEqualTo(CF_URL);
        assertThat(userAvatar(user)).isEqualTo(CF_URL);

        var cleared = avatars.select(user, null);
        assertThat(cleared.selectedAccountId()).isNull();
        assertThat(userAvatar(user)).isNull();
    }

    @Test
    void profileUpdatesCannotWriteTheAvatarDirectly() {
        Long codeforces = account(user, "CODEFORCES", "VERIFIED", CF_URL, 1);
        jdbc.update("update app_user set avatar_url = 'https://evil.example.test/a.png' where id = ?", user);
        assertThat(userAvatar(user)).isNull();
        avatars.select(user, codeforces);
        jdbc.update("update app_user set avatar_url = 'https://evil.example.test/a.png' where id = ?", user);
        assertThat(userAvatar(user)).isEqualTo(CF_URL);
    }

    @Test
    void unbindingTheChosenAccountFallsBackToTheInitial() {
        Long codeforces = account(user, "CODEFORCES", "VERIFIED", CF_URL, 1);
        avatars.select(user, codeforces);

        accountService.unbind(codeforces, user, false);

        assertThat(userSource(user)).isNull();
        assertThat(userAvatar(user)).isNull();
    }

    @Test
    void accountMovingToSomeoneElseOrLosingVerificationClearsTheChoice() {
        Long codeforces = account(user, "CODEFORCES", "VERIFIED", CF_URL, 1);
        avatars.select(user, codeforces);
        jdbc.update("update platform_account set ownership_status = 'RESOLVED' where id = ?", codeforces);
        assertThat(userSource(user)).isNull();
        assertThat(userAvatar(user)).isNull();

        jdbc.update("update platform_account set ownership_status = 'VERIFIED' where id = ?", codeforces);
        avatars.select(user, codeforces);
        jdbc.update("update platform_account set user_id = ? where id = ?", other, codeforces);
        assertThat(userSource(user)).isNull();
        assertThat(userAvatar(user)).isNull();
    }

    @Test
    void deletingAUserWithAChosenAvatarStillWorks() {
        Long codeforces = account(user, "CODEFORCES", "VERIFIED", CF_URL, 1);
        avatars.select(user, codeforces);
        jdbc.update("delete from app_user where id = ?", user);
        assertThat(jdbc.queryForObject("select count(*) from platform_account where id = ?", Integer.class, codeforces))
                .isZero();
    }

    @Test
    void refreshWaitsTwentyFourHoursAndFollowsTheSourceBackToDefaultAndAgain() {
        Long fresh = account(user, "CODEFORCES", "VERIFIED", CF_URL, 23);
        assertThat(avatars.refreshIfDue(fresh)).isFalse();
        verify(withAvatar, never()).resolveIdentity(anyString(), any());

        Long stale = account(user, "ATCODER", "VERIFIED", CF_URL, 25);
        avatars.select(user, stale);
        String uid = jdbc.queryForObject("select platform_uid from platform_account where id = ?", String.class, stale);
        // 平台不再给头像地址 → 账号头像清空，用户头像变回字标，但仍记着选的是这个账号
        when(withAvatar.resolveIdentity(anyString(), any())).thenReturn(new ResolvedIdentity(uid, "h", "h", Map.of()));
        assertThat(avatars.refreshIfDue(stale)).isTrue();
        assertThat(jdbc.queryForObject("select avatar_url from platform_account where id = ?", String.class, stale)).isNull();
        assertThat(userAvatar(user)).isNull();
        assertThat(userSource(user)).isEqualTo(stale);
        assertThat(option(stale).reason()).isEqualTo(AvatarService.REASON_NONE);
        // 刚查过：24 小时内不再查
        assertThat(avatars.refreshIfDue(stale)).isFalse();

        // 过了一天，OJ 上又换了新头像 → 自动用上
        jdbc.update("update platform_account set avatar_checked_at = now() - interval '25 hours' where id = ?", stale);
        String next = "https://img.atcoder.jp/icons/new.jpg";
        when(withAvatar.resolveIdentity(anyString(), any()))
                .thenReturn(new ResolvedIdentity(uid, "h", "h", Map.of("avatarUrl", next)));
        assertThat(avatars.refreshIfDue(stale)).isTrue();
        assertThat(userAvatar(user)).isEqualTo(next);
    }

    @Test
    void refreshFailuresKeepTheLastAvatarAndTryAgainNextTime() {
        Long stale = account(user, "CODEFORCES", "VERIFIED", CF_URL, 30);
        when(withAvatar.resolveIdentity(anyString(), any()))
                .thenThrow(new ApiException(ErrorKind.RATE_LIMITED, "限流"));
        assertThat(avatars.refreshIfDue(stale)).isTrue();
        assertThat(jdbc.queryForObject("select avatar_url from platform_account where id = ?", String.class, stale))
                .isEqualTo(CF_URL);
        // 没记检查时间：下次同步还会再试
        assertThat(jdbc.queryForObject("""
                select avatar_checked_at < now() - interval '24 hours' from platform_account where id = ?
                """, Boolean.class, stale)).isTrue();

        // 平台返回的 UID 对不上（改名、注销）：不更新
        doReturn(new ResolvedIdentity("another-uid", "h", "h", Map.of("avatarUrl", "https://x.test/a.png")))
                .when(withAvatar).resolveIdentity(anyString(), any());
        assertThat(avatars.refreshIfDue(stale)).isTrue();
        assertThat(jdbc.queryForObject("select avatar_url from platform_account where id = ?", String.class, stale))
                .isEqualTo(CF_URL);

        // 不提供头像的平台不去查
        Long hdu = account(user, "HDU", "VERIFIED", null, null);
        assertThat(avatars.refreshIfDue(hdu)).isFalse();
    }
}
