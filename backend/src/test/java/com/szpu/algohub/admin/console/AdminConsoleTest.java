package com.szpu.algohub.admin.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.TimeSupport;

/** 管理端扩充：权限、数据概览、用户管理、管理员与权限、操作日志、访问统计。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminConsoleTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    @Autowired
    AuthService auth;
    @Autowired
    AppUserMapper users;

    /** 新建一个用户；permissions 为 null 且是管理员时 = 全部权限 */
    long user(String role, String permissions) {
        String name = "console" + Long.toString(System.nanoTime(), 36);
        Long id = db.queryForObject("""
                insert into app_user (username, password_hash, display_name, role, admin_permissions)
                values (?, 'x', ?, ?, case when ?::text is null then null else string_to_array(?::text, ',')::varchar(32)[] end)
                returning id
                """, Long.class, name, name, role, permissions, permissions);
        db.update("insert into verified_login_email (user_id, email) values (?, ?)", id, name + "@test.invalid");
        return id;
    }

    String token(long userId) {
        return "Bearer " + auth.issueSession(users.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS").view().accessToken();
    }

    @Test
    void restrictedAdminOnlyReachesGrantedAreas() throws Exception {
        String schools = token(user("ADMIN", "SCHOOLS,CONTENT"));
        mvc.perform(get("/api/admin/students").header("Authorization", schools)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/announcements").header("Authorization", schools)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", schools))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("你没有「用户管理」权限"));
        mvc.perform(get("/api/admin/sync/overview").header("Authorization", schools)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/me").header("Authorization", schools))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.full").value(false))
                .andExpect(jsonPath("$.data.permissions.length()").value(2))
                .andExpect(jsonPath("$.data.permissions[0]").value("SCHOOLS"))
                .andExpect(jsonPath("$.data.catalog.length()").value(AdminPermission.values().length));

        String full = token(user("ADMIN", null));
        mvc.perform(get("/api/admin/me").header("Authorization", full))
                .andExpect(jsonPath("$.data.full").value(true))
                .andExpect(jsonPath("$.data.permissions.length()").value(AdminPermission.values().length));
        mvc.perform(get("/api/admin/me").header("Authorization", token(user("STUDENT", null)))).andExpect(status().isForbidden());
    }

    @Test
    void dashboardCountsUsersAndVisits() throws Exception {
        long student = user("STUDENT", null);
        db.update("insert into site_visit_daily (day, visitor_key, user_id, views) values (?, ?, ?, 5)",
                TimeSupport.today(), "u:" + student, student);
        db.update("insert into site_page_daily (day, route, views) values (?, 'console-test-page', 999999)", TimeSupport.today());
        String admin = token(user("ADMIN", "DASHBOARD"));
        mvc.perform(get("/api/admin/dashboard").header("Authorization", admin).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users.total").isNumber())
                .andExpect(jsonPath("$.data.users.newToday").isNumber())
                .andExpect(jsonPath("$.data.activity.visitsToday").isNumber())
                .andExpect(jsonPath("$.data.trend.length()").value(7))
                .andExpect(jsonPath("$.data.trend[6].day").value(TimeSupport.today().toString()))
                .andExpect(jsonPath("$.data.topPages[0].route").value("console-test-page"));
        Integer active = db.queryForObject("select count(distinct user_id) from site_visit_daily where day = ?",
                Integer.class, TimeSupport.today());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", admin))
                .andExpect(jsonPath("$.data.activity.activeToday").value(active))
                .andExpect(jsonPath("$.data.trend.length()").value(30));
        mvc.perform(get("/api/admin/dashboard").header("Authorization", admin).param("days", "3")).andExpect(status().isBadRequest());
    }

    @Test
    void usersListDetailDisableAndProtections() throws Exception {
        long actorId = user("ADMIN", "USERS");
        String actor = token(actorId);
        long student = user("STUDENT", null);
        String name = db.queryForObject("select username from app_user where id = ?", String.class, student);
        mvc.perform(get("/api/admin/users").header("Authorization", actor).param("q", name.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(student))
                .andExpect(jsonPath("$.data.rows[0].emailVerified").value(true))
                .andExpect(jsonPath("$.data.summary.total").isNumber());
        mvc.perform(get("/api/admin/users").header("Authorization", actor).param("role", "ADMIN").param("q", "console"))
                .andExpect(jsonPath("$.data.rows[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data.rows[0].adminPermissions[0]").exists());
        mvc.perform(get("/api/admin/users").header("Authorization", actor).param("role", "ROOT")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users/" + student).header("Authorization", actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.username").value(name))
                .andExpect(jsonPath("$.data.visits.days30").value(0));

        String studentToken = token(student);
        mvc.perform(get("/api/me").header("Authorization", studentToken)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/users/" + student + "/status").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\",\"reason\":\"测试停用\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        // 停用后原来的登录状态立刻失效
        mvc.perform(get("/api/me").header("Authorization", studentToken)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("select detail from auth_event where user_id = ? and event = 'ACCOUNT_DISABLED'",
                String.class, student)).contains("测试停用");
        mvc.perform(post("/api/admin/users/" + student + "/status").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(post("/api/admin/users/" + student + "/status").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isBadRequest());

        // 不能停用自己
        mvc.perform(post("/api/admin/users/" + actorId + "/status").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());
        // 最后一个能管理管理员的账号不能停用：其他管理员都先停用
        long keeper = user("ADMIN", null);
        db.update("update app_user set status = 'DISABLED' where role = 'ADMIN' and id not in (?, ?)", actorId, keeper);
        mvc.perform(post("/api/admin/users/" + keeper + "/status").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());

        String keeperToken = token(keeper);
        mvc.perform(post("/api/admin/users/" + keeper + "/logout").header("Authorization", actor))
                .andExpect(status().isOk())
                // Redis 里的会话不随测试事务回滚，重复跑时可能还有同一 id 的旧会话，所以只要求至少撤销了刚签发的这一个
                .andExpect(jsonPath("$.data.revokedSessions").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/admin/me").header("Authorization", keeperToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminsGrantAdjustRevokeAndProtections() throws Exception {
        long actorId = user("ADMIN", "ADMINS");
        String actor = token(actorId);
        long student = user("STUDENT", null);
        db.update("update app_user set password_hash = 'old-student-hash' where id = ?", student);

        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"permissions\":[\"CONTENT\",\"SCHOOLS\",\"CONTENT\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.adminPermissions.length()").value(2))
                .andExpect(jsonPath("$.data.adminPermissions[0]").value("SCHOOLS"));
        assertThat(db.queryForObject("select password_hash from app_user where id = ?", String.class, student)).isNull();
        mvc.perform(get("/api/admin/admins").header("Authorization", actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + student + ")].full").value(false));

        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"permissions\":null}"))
                .andExpect(jsonPath("$.data.adminPermissions").doesNotExist());
        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"permissions\":[\"ROOT\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest());

        String promoted = token(student);
        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STUDENT"));
        mvc.perform(get("/api/admin/me").header("Authorization", promoted)).andExpect(status().isUnauthorized());

        // 不能改自己；没有「管理员与权限」的管理员不能改别人
        mvc.perform(post("/api/admin/admins/" + actorId).header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isConflict());
        String usersOnly = token(user("ADMIN", "USERS"));
        mvc.perform(post("/api/admin/admins/" + student).header("Authorization", usersOnly)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writesAreAuditedIncludingRejectedOnes() throws Exception {
        long actorId = user("ADMIN", "USERS,AUDIT");
        String actor = token(actorId);
        long student = user("STUDENT", null);
        mvc.perform(post("/api/admin/users/" + student + "/logout").header("Authorization", actor)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/schools").header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"审计测试\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", actor)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("Authorization", actor).param("actorId", Long.toString(actorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[1].pattern").value("/api/admin/users/{id}/logout"))
                .andExpect(jsonPath("$.data.rows[1].target").value("id=" + student))
                .andExpect(jsonPath("$.data.rows[1].status").value(200))
                .andExpect(jsonPath("$.data.rows[0].method").value("POST"))
                .andExpect(jsonPath("$.data.rows[0].status").value(403));
        mvc.perform(get("/api/admin/audit").header("Authorization", actor).param("actorId", Long.toString(actorId)).param("q", "LOGOUT"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void visitsCountStudentsAndGuestsButNotAdmins() throws Exception {
        long student = user("STUDENT", null);
        String body = "{\"route\":\"ranking\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/visits").header("Authorization", token(student))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        assertThat(db.queryForObject("select views from site_visit_daily where day = ? and visitor_key = ?",
                Integer.class, TimeSupport.today(), "u:" + student)).isEqualTo(3);
        assertThat(db.queryForObject("select last_seen_at is not null from app_user where id = ?", Boolean.class, student)).isTrue();

        long admin = user("ADMIN", null);
        mvc.perform(post("/api/visits").header("Authorization", token(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from site_visit_daily where visitor_key = ?", Integer.class, "u:" + admin)).isZero();

        String agent = "visit-test-" + System.nanoTime();
        mvc.perform(post("/api/visits").header("User-Agent", agent).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/visits").header("User-Agent", agent).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("""
                select max(views) from site_visit_daily where day = ? and visitor_key like 'a:%' and user_id is null
                """, Integer.class, TimeSupport.today())).isGreaterThanOrEqualTo(2);

        Integer before = db.queryForObject("select coalesce(sum(views), 0) from site_page_daily where day = ?", Integer.class, TimeSupport.today());
        mvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON).content("{\"route\":\"../etc/passwd\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        assertThat(db.queryForObject("select coalesce(sum(views), 0) from site_page_daily where day = ?", Integer.class, TimeSupport.today()))
                .isEqualTo(before);
    }

    @Test
    void userRolesSeparateSchoolManagersFromStudentsAndPlatformAdmins() throws Exception {
        long actor = user("ADMIN", "USERS");
        long student = user("STUDENT", null);
        long manager = user("STUDENT", null);
        String authorization = token(actor);
        for (int n = 0; n < 2; n++) {
            long school = db.queryForObject("insert into school(name) values (?) returning id", Long.class, "Role test " + System.nanoTime());
            db.update("insert into school_administrator(user_id,school_id) values (?,?)", manager, school);
            // Promotion can leave an old school grant; platform role must take precedence.
            db.update("insert into school_administrator(user_id,school_id) values (?,?)", actor, school);
        }
        for (long id : new long[]{student, manager, actor}) {
            String name = users.findById(id).getUsername();
            String expected = id == actor ? "ADMIN" : id == manager ? "SCHOOL_ADMIN" : "STUDENT";
            for (String role : new String[]{"STUDENT", "SCHOOL_ADMIN", "ADMIN"}) {
                mvc.perform(get("/api/admin/users").header("Authorization", authorization).param("q", name).param("role", role))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(role.equals(expected) ? 1 : 0));
            }
        }
        int schoolAdmins = db.queryForObject("select count(*) from app_user u where role='STUDENT' and exists(select 1 from school_administrator sa where sa.user_id=u.id)", Integer.class);
        int students = db.queryForObject("select count(*) from app_user u where role='STUDENT' and not exists(select 1 from school_administrator sa where sa.user_id=u.id)", Integer.class);
        mvc.perform(get("/api/admin/users").header("Authorization", authorization))
            .andExpect(jsonPath("$.data.summary.schoolAdmins").value(schoolAdmins))
            .andExpect(jsonPath("$.data.summary.students").value(students));
        db.update("delete from school_administrator where user_id=?", manager);
        mvc.perform(get("/api/admin/users").header("Authorization", authorization).param("q", users.findById(manager).getUsername()).param("role", "STUDENT"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].schoolAdmin").value(false));
    }
}
