package com.szpu.algohub.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/**
 * GitHub 首次登录自动开户：与邮箱验证码首次登录一致（不设密码、不自动入校），
 * GitHub 已验证的邮箱直接作为登录邮箱；邮箱已属于别的账号时不合并也不重复开户。
 */
@SpringBootTest
@Transactional
class GithubLoginRegistrationTest {
    @Autowired GithubOauthService github;
    @Autowired AppUserMapper users;
    @Autowired JdbcTemplate jdbc;

    private static GithubOauthService.GithubUser githubUser(String id, String login, String name, String email) {
        return new GithubOauthService.GithubUser(id, login, name, "https://avatars.example.test/" + id,
                "https://github.com/" + login, email);
    }

    @Test
    void firstLoginCreatesAccountWithGithubVerifiedEmailAndNoSchool() {
        var result = github.loginOrRegister(
                githubUser("880001", "Freshman-Octo", "新生小王", "freshman.octo@example.test"), "127.0.0.1", "test");

        assertThat(result.created()).isTrue();
        AppUser user = result.user();
        assertThat(user.getUsername()).isEqualTo("gh_freshman-octo");
        assertThat(user.getDisplayName()).isEqualTo("新生小王");
        assertThat(user.getRole()).isEqualTo("STUDENT");
        AppUser stored = users.findById(user.getId());
        assertThat(stored.getPasswordHash()).isNull();
        // GitHub 已验证的邮箱就是登录邮箱：不用再到站内验证一次
        assertThat(users.verifiedEmailCount(user.getId())).isEqualTo(1);
        assertThat(users.findByVerifiedEmail("freshman.octo@example.test").getId()).isEqualTo(user.getId());
        // 和注册一样不自动加入学校，之后自己选择学校
        assertThat(jdbc.queryForObject("select count(*) from school_member where user_id=?", Integer.class, user.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_privacy where user_id=?", Integer.class, user.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select user_id from user_oauth_binding where provider='GITHUB' and provider_uid='880001'",
                Long.class)).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("select count(*) from auth_event where user_id=? and event='REGISTER' and detail like 'GITHUB:%'",
                Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void laterLoginsUseTheSameAccount() {
        var first = github.loginOrRegister(githubUser("880002", "again", null, "again@example.test"), null, null);
        var second = github.loginOrRegister(githubUser("880002", "again", null, "again@example.test"), null, null);

        assertThat(second.created()).isFalse();
        assertThat(second.user().getId()).isEqualTo(first.user().getId());
        assertThat(first.user().getDisplayName()).isEqualTo("again");
        assertThat(jdbc.queryForObject("select count(*) from user_oauth_binding where provider_uid='880002'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void withoutVerifiedEmailTheAccountStillNeedsEmailVerificationOnSite() {
        var result = github.loginOrRegister(githubUser("880003", "no-mail", "无邮箱", null), null, null);

        assertThat(result.created()).isTrue();
        assertThat(users.verifiedEmailCount(result.user().getId())).isZero();
        assertThat(users.findById(result.user().getId()).getEmail()).isNull();
    }

    /** 邮箱已属于本站另一个账号：不自动合并（防止借邮箱接管），也不再开一个重复账号 */
    @Test
    void emailOwnedByAnotherAccountIsNeitherMergedNorDuplicated() {
        Long existing = jdbc.queryForObject(
                "insert into app_user(username,password_hash,display_name) values ('gh-owner','x','原账号') returning id", Long.class);
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)", existing, "taken@example.test");
        int usersBefore = jdbc.queryForObject("select count(*) from app_user", Integer.class);

        assertThatThrownBy(() -> github.loginOrRegister(githubUser("880004", "taker", null, "taken@example.test"), null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.CONFLICT))
                .hasMessageContaining("已经注册过").hasMessageContaining("绑定 GitHub");

        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("select count(*) from user_oauth_binding where provider_uid='880004' or user_id=?",
                Integer.class, existing)).isZero();
    }

    @Test
    void takenUsernameGetsARandomSuffixWithinTheLocalRules() {
        jdbc.update("insert into app_user(username,password_hash,display_name) values ('gh_dup-login','x','占位')");

        var result = github.loginOrRegister(githubUser("880005", "dup-login", null, null), null, null);

        assertThat(result.user().getUsername()).startsWith("gh_dup-login_").hasSizeLessThanOrEqualTo(32)
                .matches("^[a-z0-9_.-]+$");
    }

    @Test
    void longNamesAreTrimmedToLocalLimits() {
        String login = "a".repeat(39);
        var result = github.loginOrRegister(githubUser("880006", login, "很长的名字".repeat(10), null), null, null);

        assertThat(result.user().getUsername()).hasSize(32).startsWith("gh_aaa");
        assertThat(result.user().getDisplayName().codePointCount(0, result.user().getDisplayName().length())).isEqualTo(32);
    }

    @Test
    void disabledAccountCannotLogInWithGithub() {
        var result = github.loginOrRegister(githubUser("880007", "stopped", null, null), null, null);
        jdbc.update("update app_user set status='DISABLED' where id=?", result.user().getId());

        assertThatThrownBy(() -> github.loginOrRegister(githubUser("880007", "stopped", null, null), null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.FORBIDDEN));
    }

    @Test
    void anotherGithubCannotSilentlyReplaceAnExistingBinding() {
        var first = github.loginOrRegister(githubUser("880008", "original", null, "original@example.test"), null, null);
        Long id = first.user().getId();
        assertThatThrownBy(() -> github.bind(id, githubUser("880009", "replacement", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.CONFLICT))
                .hasMessageContaining("先在账号安全中解绑");
        assertThat(jdbc.queryForObject("select provider_uid from user_oauth_binding where user_id=?",String.class,id)).isEqualTo("880008");
        github.unbind(id, "test", "test");
        assertThat(github.bind(id, githubUser("880009", "replacement", null, null)).getProviderUid()).isEqualTo("880009");
    }

    @Test
    void sameGithubCanRefreshItsProfileWithoutChangingOwnership() {
        var first = github.loginOrRegister(githubUser("880010", "old-login", null, null), null, null);
        var refreshed = github.bind(first.user().getId(), githubUser("880010", "renamed-login", "新昵称", null));
        assertThat(refreshed.getUserId()).isEqualTo(first.user().getId());
        assertThat(refreshed.getLogin()).isEqualTo("renamed-login");
        assertThat(jdbc.queryForObject("select count(*) from user_oauth_binding where user_id=?",Integer.class,first.user().getId())).isEqualTo(1);
    }
}
