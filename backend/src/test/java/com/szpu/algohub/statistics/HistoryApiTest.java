package com.szpu.algohub.statistics;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.szpu.algohub.auth.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HistoryApiTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AppUserMapper users;
    LoginUser login;
    Long otherUserId;
    @BeforeEach void setup() {
        Long uid=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('history-test','x','历史测试','STUDENT','ACTIVE') returning id",Long.class);
        otherUserId=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('history-other','x','其他同学','STUDENT','ACTIVE') returning id",Long.class);
        for(Long id:java.util.List.of(uid,otherUserId)) jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",id,"history-"+id+"@example.test");
        AppUser u=new AppUser(); u.setId(uid); u.setUsername("history-test");u.setPasswordHash("x");u.setDisplayName("历史测试");u.setRole("STUDENT");u.setStatus("ACTIVE");login=new LoginUser(u);
        Long aid=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status) values (?,'HDU','history-test','history-test','RESOLVED','PUBLIC','OK') returning id",Long.class,uid);
        Long pid=jdbc.queryForObject("insert into problem(platform,problem_key,title,url) values ('HDU','history-test','=danger,\"title\"','https://acm.hdu.edu.cn/showproblem.php?pid=1000') returning id",Long.class);
        jdbc.update("insert into problem_tag(problem_id,tag_raw,tag_source) values (?,'dp','OFFICIAL')",pid);
        jdbc.update("insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at) values (?,?,'HDU','history-one','Accepted',true,'2026-07-23T16:00:00Z')",aid,pid);
        jdbc.update("insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at) values (?,?,'HDU','history-two','Wrong Answer',false,'2026-07-23T15:59:59Z')",aid,pid);
    }
    @Test void requiresLoginAndValidPagination() throws Exception {
        mvc.perform(get("/api/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/history/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/history").with(user(login)).param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/history").with(user(login)).param("from","2026-08-01").param("to","2026-07-01")).andExpect(status().isBadRequest());
    }
    @Test void acceptedHistoryKeepsRepeatedAcceptedSubmissions() throws Exception {
        Long aid = jdbc.queryForObject("select id from platform_account where user_id=?", Long.class, login.getId());
        Long pid = jdbc.queryForObject("select id from problem where platform='HDU' and problem_key='history-test'", Long.class);
        jdbc.update("""
            insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at)
            values (?,?,'HDU','history-repeat','Accepted',true,'2026-07-23T16:01:00Z')
            """, aid, pid);
        mvc.perform(get("/api/history").with(user(login)).param("accepted", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.rows[0].submissionId").value("history-repeat"))
            .andExpect(jsonPath("$.data.rows[1].submissionId").value("history-one"));
    }
    @Test void includesUntimedSolvedProblemsWithoutInventingOrDuplicatingSubmissions() throws Exception {
        Long aid=jdbc.queryForObject("select id from platform_account where user_id=?",Long.class,login.getId());
        Long pid=jdbc.queryForObject("insert into problem(platform,problem_key,title,url) values ('HDU','solved-only','已通过题目','https://acm.hdu.edu.cn/showproblem.php?pid=1001') returning id",Long.class);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,first_ac_confidence,source) values (?,?,?,'HDU','UNKNOWN','TRACKER')",login.getId(),aid,pid);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,first_ac_confidence,source) select ?,?,id,'HDU','UNKNOWN','SYNC' from problem where problem_key='history-test' and platform='HDU'",login.getId(),aid);
        mvc.perform(get("/api/history").with(user(login)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.rows[2].recordKind").value("SOLVED"))
            .andExpect(jsonPath("$.data.rows[2].submittedAt").doesNotExist())
            .andExpect(jsonPath("$.data.rows[2].submissionId").doesNotExist());
        mvc.perform(get("/api/history").with(user(login)).param("from","2026-07-01"))
            .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/history").with(user(login)).param("accepted","false"))
            .andExpect(jsonPath("$.data.total").value(1));
        String csv=mvc.perform(get("/api/history/export").with(user(login))).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("recordKind","SOLVED","solved-only");
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,aid)).isEqualTo(2);
        AppUser other=new AppUser();other.setId(otherUserId);other.setRole("STUDENT");other.setStatus("ACTIVE");
        mvc.perform(get("/api/history").with(user(new LoginUser(other)))).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void filtersBeijingDatesAndPagesWithoutDuplicates() throws Exception {
        mvc.perform(get("/api/history").with(user(login)).param("from","2026-07-24").param("to","2026-07-24"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].submissionId").value("history-one"));
        mvc.perform(get("/api/history").with(user(login)).param("size","1").param("page","2"))
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.rows[0].submissionId").value("history-two"));
        mvc.perform(get("/api/history").with(user(login)).param("accepted","true")).andExpect(jsonPath("$.data.total").value(1));
        AppUser stranger=new AppUser(); stranger.setId(otherUserId);stranger.setRole("STUDENT");stranger.setStatus("ACTIVE");
        mvc.perform(get("/api/history").with(user(new LoginUser(stranger))).param("userId",login.getId().toString()))
            .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void exportsOptionalTagsEscapesCellsAndExcludesSecrets() throws Exception {
        String plain=mvc.perform(get("/api/history/export").with(user(login))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(plain).contains("\"'=danger,\"\"title\"\"\"").doesNotContain(",tags","password","token","username","studentNo");
        String tagged=mvc.perform(get("/api/history/export").with(user(login)).param("includeTags","true").param("accepted","true")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(tagged).contains(",tags\r\n","\"dp\"").doesNotContain("history-two");
    }
    /** 在测试事务内创建管理员，不依赖其他测试可能清理的引导账号。 */
    LoginUser admin() {
        String name = "history-admin-" + java.util.UUID.randomUUID();
        Long id = jdbc.queryForObject("""
            insert into app_user(username,password_hash,display_name,role,status,admin_permissions)
            values (?,'x','历史测试管理员','ADMIN','ACTIVE',null) returning id
            """, Long.class, name);
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)", id, name + "@example.test");
        return new LoginUser(users.findById(id));
    }
    @Test void tagMappingRequiresAdminAndValidatesInput() throws Exception {
        mvc.perform(get("/api/admin/tag-mappings").with(user(login))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/tag-mappings").with(user(admin())).contentType("application/json").content("{\"platform\":\"HDU\",\"tagRaw\":\"dp\",\"category\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/tag-mappings").with(user(admin())).contentType("application/json").content("{\"platform\":\"HDU\",\"tagRaw\":\"dp\",\"category\":\"动态规划\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select category from tag_category_mapping where platform='HDU' and tag_raw='dp'",String.class)).isEqualTo("动态规划");
        mvc.perform(get("/api/admin/tag-mappings").with(user(admin()))).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/tag-mappings").with(user(login)).param("platform","HDU").param("tagRaw","dp"))
            .andExpect(status().isForbidden());
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(delete("/api/admin/tag-mappings").with(user(admin())).param("platform","HDU").param("tagRaw","dp"))
                .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from tag_category_mapping where platform='HDU' and tag_raw='dp'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from problem_tag t join problem p on p.id=t.problem_id where p.platform='HDU' and p.problem_key='history-test' and t.tag_raw='dp'",Integer.class)).isEqualTo(1);
    }
}
