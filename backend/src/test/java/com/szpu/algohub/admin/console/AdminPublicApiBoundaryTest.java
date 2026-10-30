package com.szpu.algohub.admin.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.LoginUser;

/** 每例事务回滚，只用新建的测试记录；普通接口与管理权限同时验证。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminPublicApiBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;

    long person(String role, String permissions) {
        String name = "boundary-" + UUID.randomUUID().toString().replace("-", "");
        long id = db.queryForObject("""
                insert into app_user(username,display_name,role,admin_permissions)
                values(?,?,?,case when ?::text is null then null else string_to_array(?::text,',')::varchar(32)[] end)
                returning id
                """, Long.class, name, name, role, permissions, permissions);
        db.update("insert into verified_login_email(user_id,email) values(?,?)", id, name + "@example.test");
        return id;
    }

    LoginUser principal(long id) { return new LoginUser(users.findById(id)); }

    long account(long owner) {
        String uid = UUID.randomUUID().toString();
        long id = db.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status)
                values(?,'CODEFORCES',?,?,'VERIFIED') returning id
                """, Long.class, owner, uid, "test-" + uid);
        db.update("insert into platform_credential(account_id,cipher_text) values(?,'synthetic-test-cipher')", id);
        return id;
    }

    long problem() {
        return db.queryForObject("""
                insert into problem(platform,problem_key,title,url)
                values('CODEFORCES',?,'boundary problem','https://example.test/problem') returning id
                """, Long.class, UUID.randomUUID().toString());
    }

    long problemList(Long owner, boolean visible, long problem) {
        long id = db.queryForObject("""
                insert into problem_list(code,title,owner_id,source,visible,share_scope)
                values(?,'original title',?,'MANUAL',?,?) returning id
                """, Long.class, UUID.randomUUID().toString(), owner, visible, owner == null ? "PUBLIC" : "PRIVATE");
        db.update("insert into problem_list_item(list_id,problem_id,position,note) values(?,?,0,'original note')", id, problem);
        return id;
    }

    @Test void publicAccountOperationsRejectEvenFullAdminsOnAnotherUsersAccount() throws Exception {
        long target = account(person("STUDENT", null));
        for (long actor : List.of(person("ADMIN", "CONTENT"), person("ADMIN", null))) {
            mvc.perform(post("/api/accounts/" + target + "/sync").with(user(principal(actor))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/accounts/" + target).with(user(principal(actor))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/accounts/" + target + "/credential").with(user(principal(actor))))
                    .andExpect(status().isForbidden());
        }
        assertThat(db.queryForObject("select count(*) from platform_account where id=?", Integer.class, target)).isEqualTo(1);
        assertThat(db.queryForObject("select cipher_text from platform_credential where account_id=?", String.class, target))
                .isEqualTo("synthetic-test-cipher");
        assertThat(db.queryForObject("select count(*) from sync_task where account_id=?", Integer.class, target)).isZero();
    }

    @Test void publicAccountOperationsStillAllowAdminToManageTheirOwnAccount() throws Exception {
        long actor = person("ADMIN", "CONTENT");
        long own = account(actor);
        mvc.perform(post("/api/accounts/" + own + "/sync").with(user(principal(actor)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from sync_task where account_id=?", Integer.class, own)).isEqualTo(1);
        mvc.perform(delete("/api/accounts/" + own).with(user(principal(actor)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from platform_account where id=?", Integer.class, own)).isZero();
    }

    @Test void adminAccountOperationsRequireSyncPermissionAndAreAudited() throws Exception {
        long target = account(person("STUDENT", null));
        long restricted = person("ADMIN", "CONTENT");
        String route = "/api/admin/sync/accounts/" + target;
        mvc.perform(post(route + "/retry").with(user(principal(restricted)))).andExpect(status().isForbidden());
        mvc.perform(delete(route).with(user(principal(restricted)))).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from platform_account where id=?", Integer.class, target)).isEqualTo(1);
        long operator = person("ADMIN", "SYNC");
        mvc.perform(post(route + "/retry").with(user(principal(operator)))).andExpect(status().isOk());
        mvc.perform(delete(route).with(user(principal(operator)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from platform_account where id=?", Integer.class, target)).isZero();
        assertThat(db.queryForObject("""
                select count(*) from admin_audit_log where actor_id=? and method='DELETE'
                  and pattern='/api/admin/sync/accounts/{accountId}' and status=200
                """, Integer.class, operator)).isEqualTo(1);
    }

    @Test void publicProblemListEndpointsCannotBypassOwnershipOrVisibilityWithAdminRole() throws Exception {
        long problem = problem();
        long otherList = problemList(person("STUDENT", null), true, problem);
        long hiddenSystem = problemList(null, false, problem);
        for (long actor : List.of(person("ADMIN", "CONTENT"), person("ADMIN", null))) {
            for (long target : List.of(otherList, hiddenSystem)) {
                String route = "/api/problem-lists/" + target;
                for (MockHttpServletRequestBuilder request : List.of(
                        get(route), get(route + "/leaderboard"), post(route + "/favorite"), delete(route + "/favorite"),
                        post(route).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"changed\"}"),
                        post(route + "/items").contentType(MediaType.APPLICATION_JSON).content("{\"problemIds\":[" + problem + "]}"),
                        delete(route + "/items/" + problem),
                        post(route + "/reorder").contentType(MediaType.APPLICATION_JSON).content("{\"problemIds\":[" + problem + "]}"),
                        delete(route))) {
                    mvc.perform(request.with(user(principal(actor)))).andExpect(status().isForbidden());
                }
                assertThat(db.queryForObject("select title from problem_list where id=?", String.class, target)).isEqualTo("original title");
                assertThat(db.queryForObject("select note from problem_list_item where list_id=?", String.class, target)).isEqualTo("original note");
            }
        }
    }

    @Test void adminProblemListManagementKeepsDedicatedPermissionAndPrivateDetailAccess() throws Exception {
        long target = problemList(person("STUDENT", null), true, problem());
        long restricted = person("ADMIN", "CONTENT");
        String route = "/api/admin/problem-lists/" + target;
        mvc.perform(get(route).with(user(principal(restricted)))).andExpect(status().isForbidden());
        mvc.perform(delete(route).with(user(principal(restricted)))).andExpect(status().isForbidden());
        long operator = person("ADMIN", "PROBLEMS");
        mvc.perform(get(route).with(user(principal(operator)))).andExpect(status().isOk());
        mvc.perform(post(route).with(user(principal(operator))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"reviewed title\"}")).andExpect(status().isOk());
        assertThat(db.queryForObject("select title from problem_list where id=?", String.class, target)).isEqualTo("reviewed title");
        mvc.perform(delete(route).with(user(principal(operator)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from problem_list where id=?", Integer.class, target)).isZero();
    }

    @Test void publicProblemListOwnerCanStillReadFavoriteEditAndDelete() throws Exception {
        long actor = person("ADMIN", "CONTENT");
        long target = problemList(actor, true, problem());
        String route = "/api/problem-lists/" + target;
        mvc.perform(get(route).with(user(principal(actor)))).andExpect(status().isOk());
        mvc.perform(post(route + "/favorite").with(user(principal(actor)))).andExpect(status().isOk());
        mvc.perform(post(route).with(user(principal(actor))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"my new title\"}")).andExpect(status().isOk());
        assertThat(db.queryForObject("select title from problem_list where id=?", String.class, target)).isEqualTo("my new title");
        mvc.perform(delete(route).with(user(principal(actor)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from problem_list where id=?", Integer.class, target)).isZero();
    }
}
