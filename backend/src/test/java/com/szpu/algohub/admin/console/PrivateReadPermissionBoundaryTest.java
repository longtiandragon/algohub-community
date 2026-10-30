package com.szpu.algohub.admin.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthDtos;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.LoginUser;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.statistics.DailyStatMapper;

/** 在实际公共 HTTP 边界校验模块权限；夹具均为新建记录，事务结束回滚。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PrivateReadPermissionBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired AuthService auth;
    @Autowired DailyStatMapper daily;
    long target;
    LocalDate today;

    @BeforeEach void fixture() {
        today = TimeSupport.today();
        target = person("STUDENT", null);
        db.update("insert into user_privacy(user_id,profile_visibility,show_email) values(?,'PRIVATE',false)", target);
        solve("VERIFIED", today);
        solve("RESOLVED", today.minusDays(1));
    }

    long person(String role, String permissions) {
        String name = "private-read-" + UUID.randomUUID().toString().replace("-", "");
        long id = db.queryForObject("""
                insert into app_user(username,display_name,email,role,admin_permissions)
                values(?,?,?,?,case when ?::text is null then null else string_to_array(?::text,',')::varchar(32)[] end)
                returning id
                """, Long.class, name, name, name + "@example.test", role, permissions, permissions);
        db.update("insert into verified_login_email(user_id,email) values(?,?)", id, name + "@example.test");
        return id;
    }

    LoginUser principal(long id) { return new LoginUser(users.findById(id)); }

    void solve(String ownership, LocalDate day) {
        String key = UUID.randomUUID().toString();
        long account = db.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status)
                values(?,'CODEFORCES',?,?,?) returning id
                """, Long.class, target, key, key, ownership);
        long problem = db.queryForObject("""
                insert into problem(platform,problem_key,title,url,difficulty_value,difficulty_source)
                values('CODEFORCES',?,?,'https://example.test/problem',1000,'OFFICIAL') returning id
                """, Long.class, key, key);
        Timestamp at = Timestamp.from(day.atTime(12, 0).atZone(TimeSupport.CAMPUS_ZONE).toInstant());
        db.update("""
                insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence)
                values(?,?,?,'CODEFORCES',?,'EXACT')
                """, target, account, problem, at);
        db.update("""
                insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at)
                values(?,?,'CODEFORCES',?,'AC',true,?)
                """, account, problem, key, at);
        daily.rebuildSubmissions(account);
        daily.rebuildAcSubmissions(account);
        daily.rebuildUniqueAc(account);
        daily.rebuildFirstAc(account);
    }

    List<String> privateRoutes() {
        return List.of("/api/me/profile/" + target, "/api/stats/profile/" + target,
                "/api/stats/heatmap?userId=" + target, "/api/activity/achievements/" + target,
                "/api/activity/streak/" + target, "/api/history/user/" + target,
                "/api/stats/rating-history/" + target, "/api/accounts/luogu-extra/" + target,
                "/api/accounts/nowcoder-tracker/cache/" + target);
    }

    @Test void privateProfileAndDerivedReadRoutesRequireUsersPermission() throws Exception {
        for (String permissions : List.of("CONTENT", "SYNC", "PROBLEMS")) {
            long restricted = person("ADMIN", permissions);
            for (String route : privateRoutes()) {
                mvc.perform(get(route).with(user(principal(restricted)))).andExpect(status().isNotFound());
            }
        }
        for (long allowed : List.of(person("ADMIN", "USERS"), person("ADMIN", null))) {
            for (String route : privateRoutes()) {
                mvc.perform(get(route).with(user(principal(allowed)))).andExpect(status().isOk());
            }
        }
    }

    @Test void restrictedAdminGetsPublicVerifiedStatisticsAndHiddenEmailWhileUsersGetsManagementView() throws Exception {
        auth.updatePrivacy(target, new AuthDtos.PrivacyRequest("PUBLIC", null, null, null, false), "test", "JUnit");
        long restricted = person("ADMIN", "CONTENT");
        mvc.perform(get("/api/me/profile/" + target).with(user(principal(restricted))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").doesNotExist());
        mvc.perform(get("/api/stats/profile/" + target).with(user(principal(restricted))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalSolvedAcrossPlatforms").value(1))
                .andExpect(jsonPath("$.data.recentAccepted.length()").value(1));
        mvc.perform(get("/api/stats/heatmap").param("userId", Long.toString(target)).with(user(principal(restricted))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.metrics.AC_SUBMISSIONS['" + today + "']").value(1))
                .andExpect(jsonPath("$.data.metrics.AC_SUBMISSIONS['" + today.minusDays(1) + "']").doesNotExist());
        mvc.perform(get("/api/activity/achievements/" + target).with(user(principal(restricted))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.achievements[?(@.code == 'SOLVE_10')].progress")
                        .value(org.hamcrest.Matchers.contains(1)));
        mvc.perform(get("/api/activity/streak/" + target).with(user(principal(restricted))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeDays").value(1));

        long operator = person("ADMIN", "USERS");
        mvc.perform(get("/api/me/profile/" + target).with(user(principal(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(users.findById(target).getEmail()));
        mvc.perform(get("/api/stats/profile/" + target).with(user(principal(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalSolvedAcrossPlatforms").value(2));
        mvc.perform(get("/api/stats/heatmap").param("userId", Long.toString(target)).with(user(principal(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.metrics.AC_SUBMISSIONS['" + today.minusDays(1) + "']").value(1));
        mvc.perform(get("/api/activity/achievements/" + target).with(user(principal(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.achievements[?(@.code == 'SOLVE_10')].progress")
                        .value(org.hamcrest.Matchers.contains(2)));
        mvc.perform(get("/api/activity/streak/" + target).with(user(principal(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeDays").value(2));
    }

    @Test void publicTrainingDraftReadsRequireProblemsPermission() throws Exception {
        long draft = db.queryForObject("""
                insert into training_activity(title,start_at,end_at,published,scope)
                values('synthetic draft',now(),now()+interval '1 day',false,'PUBLIC') returning id
                """, Long.class);
        for (String permissions : List.of("CONTENT", "USERS", "SYNC")) {
            long restricted = person("ADMIN", permissions);
            mvc.perform(get("/api/activities/" + draft).with(user(principal(restricted)))).andExpect(status().isNotFound());
            mvc.perform(get("/api/activities").with(user(principal(restricted))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.id == " + draft + ")]")
                            .value(org.hamcrest.Matchers.empty()));
        }
        for (long allowed : List.of(person("ADMIN", "PROBLEMS"), person("ADMIN", null))) {
            mvc.perform(get("/api/activities/" + draft).with(user(principal(allowed)))).andExpect(status().isOk());
            mvc.perform(get("/api/activities").with(user(principal(allowed))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.id == " + draft + ")]")
                            .value(org.hamcrest.Matchers.hasSize(1)));
        }
    }

    @Test void revokingUsersPermissionTakesEffectOnTheNextPublicRead() throws Exception {
        long operator = person("ADMIN", "USERS");
        LoginUser unchangedPrincipal = principal(operator);
        mvc.perform(get("/api/me/profile/" + target).with(user(unchangedPrincipal))).andExpect(status().isOk());
        db.update("update app_user set admin_permissions=array['CONTENT']::varchar(32)[] where id=?", operator);
        mvc.perform(get("/api/me/profile/" + target).with(user(unchangedPrincipal))).andExpect(status().isNotFound());
    }
}
