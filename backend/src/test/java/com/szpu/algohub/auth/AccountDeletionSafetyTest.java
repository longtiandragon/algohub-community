package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 仅在可回滚测试事务中构造最后 keeper，不依赖也不永久改变测试库原管理员。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountDeletionSafetyTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired AuthService auth;
    @Autowired SecurityActionService actions;

    long admin(String permissions) {
        String name = "delete-safety-" + UUID.randomUUID().toString().replace("-", "");
        return db.queryForObject("""
                insert into app_user(username,display_name,role,admin_permissions)
                values(?,?,'ADMIN',case when ?::text is null then null else string_to_array(?::text,',')::varchar(32)[] end)
                returning id
                """, Long.class, name, name, permissions, permissions);
    }

    void isolateKeepers() {
        db.update("update app_user set admin_permissions=array['CONTENT']::varchar(32)[] where role='ADMIN'");
    }

    @Test void verifiedSelfDeletionCannotRemoveLastPermissionKeeperWhileOtherAdminsRemain() throws Exception {
        isolateKeepers();
        long keeper = admin("ADMINS");
        admin("CONTENT");
        AppUser target = users.findById(keeper);
        String proof = actions.grant(target, "DELETE_ACCOUNT");
        mvc.perform(post("/api/me/delete").with(user(new LoginUser(target))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmUsername\":\"" + target.getUsername() + "\",\"actionToken\":\"" + proof + "\"}"))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, keeper)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from app_user where role='ADMIN' and status='ACTIVE'", Integer.class))
                .isGreaterThan(1);
    }

    @Test void fullPermissionAdminIsAlsoProtectedAsLastKeeper() {
        isolateKeepers();
        long keeper = admin(null);
        admin("CONTENT");
        assertThatThrownBy(() -> auth.deleteVerifiedAccount(keeper, users.findById(keeper).getUsername(), "test", "JUnit"))
                .hasMessageContaining("最后一个拥有管理员与权限管理能力");
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, keeper)).isEqualTo(1);
    }

    @Test void keeperCanDeleteWhenAnotherActiveKeeperRemains() {
        isolateKeepers();
        long first = admin("ADMINS");
        long remaining = admin(null);
        auth.deleteVerifiedAccount(first, users.findById(first).getUsername(), "test", "JUnit");
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, first)).isZero();
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, remaining)).isEqualTo(1);
    }

    @Test void disabledKeeperDoesNotPermitLastActiveKeeperDeletion() {
        isolateKeepers();
        long keeper = admin("ADMINS");
        long disabled = admin("ADMINS");
        db.update("update app_user set status='DISABLED' where id=?", disabled);
        assertThatThrownBy(() -> auth.deleteVerifiedAccount(keeper, users.findById(keeper).getUsername(), "test", "JUnit"))
                .hasMessageContaining("最后一个拥有管理员与权限管理能力");
    }

    @Test void nonKeeperCanDeleteWithoutRemovingRemainingKeeper() {
        isolateKeepers();
        long keeper = admin("ADMINS");
        long target = admin("CONTENT");
        auth.deleteVerifiedAccount(target, users.findById(target).getUsername(), "test", "JUnit");
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, target)).isZero();
        assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, keeper)).isEqualTo(1);
    }
}
