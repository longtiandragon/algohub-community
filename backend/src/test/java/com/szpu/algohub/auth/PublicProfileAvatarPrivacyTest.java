package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** OJ 头像 URL 可含账号标识，关闭账号展示时对外使用默认头像。 */
@SpringBootTest
@Transactional
class PublicProfileAvatarPrivacyTest {
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;

    @Test void hidingAccountsSuppressesPublicAvatarButKeepsPrivateAndStoredChoice() {
        String name = "avatar-privacy-" + UUID.randomUUID().toString().replace("-", "");
        String avatar = "https://example.test/avatar/synthetic-oj-uid.png";
        long target = db.queryForObject("""
                insert into app_user(username,display_name,role)
                values(?,?,'STUDENT') returning id
                """, Long.class, name, name);
        long account = db.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,avatar_url)
                values(?,'CODEFORCES',?,?,'VERIFIED',?) returning id
                """, Long.class, target, name, name, avatar);
        db.update("update app_user set avatar_account_id=? where id=?", account, target);
        db.update("insert into user_privacy(user_id,profile_visibility,show_accounts) values(?,'PUBLIC',false)", target);
        assertThat(auth.publicProfile(target, 0L, false).avatarUrl()).isNull();
        assertThat(auth.publicProfile(target, 0L, true).avatarUrl()).isNull();
        assertThat(auth.publicProfile(target, target, false).avatarUrl()).isEqualTo(avatar);
        assertThat(auth.me(target).avatarUrl()).isEqualTo(avatar);
        assertThat(db.queryForObject("select avatar_url from app_user where id=?", String.class, target)).isEqualTo(avatar);
        auth.updatePrivacy(target, new AuthDtos.PrivacyRequest(null, null, true, null, null), "test", "JUnit");
        assertThat(auth.publicProfile(target, 0L, false).avatarUrl()).isEqualTo(avatar);
    }
}
