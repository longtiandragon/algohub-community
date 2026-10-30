package com.szpu.algohub.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.activity.AchievementMapper;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.AuthDtos;
import com.szpu.algohub.common.TimeSupport;

/**
 * 多学校隔离验收：两所学校、多位同学，全部走真实接口与登录令牌。
 * 跨学校访问、私人资源访问都要被拦住；学校身份只看服务端成员记录，请求里带的学校编号不起作用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolIsolationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private AchievementMapper achievementMapper;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> users = new ArrayList<>();
    private final String tag = "iso" + Long.toString(System.nanoTime(), 36);

    private Long schoolA;
    private Long schoolB;
    private Long a1;
    private Long a2;
    private Long b1;
    private Long noSchool;
    private Long admin;
    private Long p1;
    private Long p2;
    private final Instant solvedAt = Instant.now().minus(2, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        // 两所学校都现建，不和其他测试留在默认学校里的数据互相干扰
        schoolA = jdbc.queryForObject("insert into school (name, invite_code) values (?, ?) returning id",
                Long.class, "隔离测试甲校", tag + "-A");
        schoolB = jdbc.queryForObject("insert into school (name, invite_code) values (?, ?) returning id",
                Long.class, "隔离测试乙校", tag + "-B");
        a1 = user("A1", schoolA, "STUDENT");
        a2 = user("A2", schoolA, "STUDENT");
        b1 = user("B1", schoolB, "STUDENT");
        noSchool = user("N", null, "STUDENT");
        admin = user("ADMIN", null, "ADMIN");
        p1 = problem("1");
        p2 = problem("2");
        Long acc1 = account(a1);
        Long acc2 = account(a2);
        Long accB = account(b1);
        solve(a1, acc1, p1, 1);
        solve(a2, acc2, p2, 1);
        solve(b1, accB, p1, 2);
        solve(b1, accB, p2, 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from training_activity where title like ?", tag + "%");
        for (Long id : users) {
            jdbc.update("delete from app_user where id = ?", id);
        }
        jdbc.update("delete from problem where problem_key like ?", tag + "%");
        jdbc.update("delete from school where id in (?, ?)", schoolA, schoolB);
    }

    private Long user(String name, Long school, String role) {
        Long id = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name, role) values (?, 'x', ?, ?) returning id
                """, Long.class, tag + "-" + name, tag + "-" + name, role);
        users.add(id);
        jdbc.update("insert into user_privacy (user_id) values (?)", id);
        jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", id, id + "@iso.invalid");
        if (school != null) {
            jdbc.update("insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')", school, id);
        }
        return id;
    }

    private Long account(Long userId) {
        return jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status,
                                              supports_history, history_complete, last_success_at)
                values (?, 'CODEFORCES', ?, ?, 'VERIFIED', true, true, now()) returning id
                """, Long.class, userId, tag + "-uid-" + userId, tag + "-h-" + userId);
    }

    private Long problem(String key) {
        return jdbc.queryForObject("""
                insert into problem (platform, problem_key, title, url) values ('CODEFORCES', ?, ?, ?) returning id
                """, Long.class, tag + key, "隔离题 " + key, "https://codeforces.com/" + key);
    }

    /** 通过一道题，并记一条本周首次通过的日统计（供区间榜使用）。 */
    private void solve(Long userId, Long accountId, Long problemId, int firstAcToday) {
        jdbc.update("""
                insert into user_solved_problem (user_id, account_id, problem_id, platform, earliest_known_ac_at,
                                                 first_ac_confidence)
                values (?, ?, ?, 'CODEFORCES', ?, 'EXACT')
                """, userId, accountId, problemId, java.sql.Timestamp.from(solvedAt));
        if (firstAcToday > 0) {
            jdbc.update("""
                    insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
                    values (?, ?, 'CODEFORCES', ?, 'FIRST_AC', ?, 'SUBMISSION')
                    on conflict (account_id, stat_date, metric) do update set value = excluded.value
                    """, userId, accountId, TimeSupport.today(), firstAcToday);
        }
    }

    private String token(Long userId) {
        return authService.issueSession(userMapper.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS")
                .view().accessToken();
    }

    private MvcResult call(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token(userId))).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private static Set<Long> userIds(JsonNode rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.path("userId").asLong()));
        return ids;
    }

    @Test
    void schoolFeedAndSummaryOnlyShowTheViewersSchool() throws Exception {
        JsonNode feedA = data(call(a2, get("/api/activity/feed")));
        assertThat(userIds(feedA.path("items"))).containsExactlyInAnyOrder(a1, a2);

        JsonNode feedB = data(call(b1, get("/api/activity/feed")));
        assertThat(userIds(feedB.path("items"))).containsExactly(b1);

        // 指定看外校同学的动态也拿不到
        JsonNode peek = data(call(a1, get("/api/activity/feed").param("userId", String.valueOf(b1))));
        assertThat(peek.path("items")).isEmpty();

        JsonNode none = data(call(noSchool, get("/api/activity/feed")));
        assertThat(none.path("items")).isEmpty();
        assertThat(none.path("note").asText()).contains("选择学校");

        assertThat(data(call(a1, get("/api/activity/summary"))).path("activeUsers").asLong()).isEqualTo(2);
        assertThat(data(call(b1, get("/api/activity/summary"))).path("activeUsers").asLong()).isEqualTo(1);
    }

    @Test
    void publicFeedSupportsCrossSchoolAndMultipleGradesWithoutSchoolMembership() throws Exception {
        jdbc.update("update school_member set enrollment_year=2024 where user_id=?", a1);
        jdbc.update("update school_member set enrollment_year=2025 where user_id=?", b1);
        var publicFeed = data(call(noSchool, get("/api/activity/feed").param("scope", "PUBLIC")
                .param("years", "2024,2025").param("limit", "100")));
        assertThat(userIds(publicFeed.path("items"))).contains(a1, b1).doesNotContain(a2);
        var schoolFeed = data(call(a1, get("/api/activity/feed").param("scope", "SCHOOL").param("years", "2024,2025")));
        assertThat(userIds(schoolFeed.path("items"))).containsExactly(a1);
        var summary = data(call(noSchool, get("/api/activity/summary").param("scope", "PUBLIC").param("years", "2024,2025")));
        assertThat(summary.path("totalEvents").asLong()).isGreaterThanOrEqualTo(3);
        authService.updatePrivacy(b1, new AuthDtos.PrivacyRequest(null,null,null,null,null,false), "test", "test");
        assertThat(data(call(noSchool, get("/api/activity/feed").param("scope", "PUBLIC").param("userId", b1.toString()))).path("items")).isEmpty();
        assertThat(data(call(b1, get("/api/activity/feed").param("scope", "SCHOOL"))).path("items")).hasSize(2);
    }

    @Test
    void rankingUsesServerSideSchoolAndIgnoresClientSchoolId() throws Exception {
        JsonNode ranking = data(call(a1, get("/api/stats/ranking").param("type", "WEEK").param("metric", "FIRST_AC")
                .param("schoolId", String.valueOf(schoolB))));
        assertThat(userIds(ranking.path("rows"))).containsExactlyInAnyOrder(a1, a2);
        assertThat(ranking.path("participantCount").asLong()).isEqualTo(2);

        JsonNode rankingB = data(call(b1, get("/api/stats/ranking").param("type", "TOTAL")));
        assertThat(userIds(rankingB.path("rows"))).containsExactly(b1);
    }

    @Test
    void schoolOnlyProfileIsHiddenFromOtherSchools() throws Exception {
        jdbc.update("update user_privacy set profile_visibility = 'SCHOOL' where user_id = ?", a1);

        assertThat(call(a2, get("/api/stats/profile/" + a1)).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(b1, get("/api/stats/profile/" + a1)).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(noSchool, get("/api/stats/profile/" + a1)).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(b1, get("/api/stats/heatmap").param("userId", String.valueOf(a1))).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(call(b1, get("/api/me/profile/" + a1)).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(call(a2, get("/api/me/profile/" + a1))).path("visible").asBoolean()).isTrue();
    }

    @Test
    void problemListScopesAndLeaderboardsStayWithinSchool() throws Exception {
        // 学生请求里带了外校编号：服务端只按作者自己的学校保存
        JsonNode schoolList = data(call(a1, postJson("/api/problem-lists",
                Map.of("title", tag + " 本校题单", "shareScope", "SCHOOL", "schoolId", schoolB))));
        long schoolListId = schoolList.path("id").asLong();
        assertThat(schoolList.path("schoolId").asLong()).isEqualTo(schoolA);

        assertThat(call(a2, get("/api/problem-lists/" + schoolListId)).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(b1, get("/api/problem-lists/" + schoolListId)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(b1, get("/api/problem-lists/" + schoolListId + "/leaderboard")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(ids(data(call(b1, get("/api/problem-lists"))))).doesNotContain(schoolListId);
        assertThat(ids(data(call(a2, get("/api/problem-lists"))))).contains(schoolListId);

        long privateList = data(call(a1, postJson("/api/problem-lists",
                Map.of("title", tag + " 私人题单", "shareScope", "PRIVATE")))).path("id").asLong();
        assertThat(call(a2, get("/api/problem-lists/" + privateList)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(a2, post("/api/problem-lists/" + privateList + "/favorite")).getResponse().getStatus())
                .isEqualTo(403);

        long publicList = data(call(a1, postJson("/api/problem-lists",
                Map.of("title", tag + " 全站题单", "shareScope", "PUBLIC")))).path("id").asLong();
        data(call(a1, postJson("/api/problem-lists/" + publicList + "/items", Map.of("problemIds", List.of(p1, p2)))));
        assertThat(call(b1, get("/api/problem-lists/" + publicList)).getResponse().getStatus()).isEqualTo(200);
        // 全站题单的完成榜仍只列查看者本校
        assertThat(userIds(data(call(b1, get("/api/problem-lists/" + publicList + "/leaderboard"))).path("rows")))
                .containsExactly(b1);
        assertThat(userIds(data(call(a2, get("/api/problem-lists/" + publicList + "/leaderboard"))).path("rows")))
                .containsExactlyInAnyOrder(a1, a2);
        JsonNode noSchoolBoard = data(call(noSchool, get("/api/problem-lists/" + publicList + "/leaderboard")));
        assertThat(noSchoolBoard.path("rows")).isEmpty();

        // 没有学校的同学不能分享到"本校"
        MvcResult rejected = call(noSchool, postJson("/api/problem-lists",
                Map.of("title", tag + " 无校", "shareScope", "SCHOOL")));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    }

    private static List<Long> ids(JsonNode lists) {
        List<Long> ids = new ArrayList<>();
        lists.forEach(list -> ids.add(list.path("id").asLong()));
        return ids;
    }

    @Test
    void activitiesAreScopedAndTheirLeaderboardsListOnlyTheViewersSchool() throws Exception {
        long listId = data(call(a1, postJson("/api/problem-lists",
                Map.of("title", tag + " 活动题单", "shareScope", "PUBLIC")))).path("id").asLong();
        data(call(a1, postJson("/api/problem-lists/" + listId + "/items", Map.of("problemIds", List.of(p1, p2)))));
        String start = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        String end = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // 学生不能调管理员接口
        assertThat(call(a1, postJson("/api/admin/activities", Map.of("title", tag + " 冒充", "listId", listId,
                "startAt", start, "endAt", end, "published", true, "scope", "PUBLIC"))).getResponse().getStatus())
                .isEqualTo(403);

        data(call(admin, postJson("/api/admin/activities", Map.of("title", tag + " A 校训练", "listId", listId,
                "startAt", start, "endAt", end, "published", true, "scope", "SCHOOL", "schoolId", schoolA))));
        data(call(admin, postJson("/api/admin/activities", Map.of("title", tag + " 全站训练", "listId", listId,
                "startAt", start, "endAt", end, "published", true, "scope", "PUBLIC"))));
        long schoolActivity = jdbc.queryForObject("select id from training_activity where title = ?", Long.class,
                tag + " A 校训练");
        long publicActivity = jdbc.queryForObject("select id from training_activity where title = ?", Long.class,
                tag + " 全站训练");

        assertThat(activityIds(data(call(a1, get("/api/activities"))))).contains(schoolActivity, publicActivity);
        assertThat(activityIds(data(call(b1, get("/api/activities"))))).contains(publicActivity)
                .doesNotContain(schoolActivity);
        assertThat(activityIds(data(call(noSchool, get("/api/activities"))))).contains(publicActivity)
                .doesNotContain(schoolActivity);
        assertThat(call(b1, get("/api/activities/" + schoolActivity)).getResponse().getStatus()).isEqualTo(404);

        assertThat(userIds(data(call(b1, get("/api/activities/" + publicActivity))).path("leaderboard")))
                .containsExactly(b1);
        assertThat(userIds(data(call(a2, get("/api/activities/" + publicActivity))).path("leaderboard")))
                .containsExactlyInAnyOrder(a1, a2);

        // 面向学校的活动必须指定有效学校
        MvcResult invalid = call(admin, postJson("/api/admin/activities", Map.of("title", tag + " 缺学校",
                "listId", listId, "startAt", start, "endAt", end, "published", true, "scope", "SCHOOL")));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }

    private static List<Long> activityIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    @Test
    void campusSolvedCountIsPerSchool() {
        assertThat(achievementMapper.campusSolvedCount(schoolA, p1)).isEqualTo(1);
        assertThat(achievementMapper.campusSolvedCount(schoolB, p1)).isEqualTo(1);
        assertThat(achievementMapper.campusSolvedCount(schoolA, p2)).isEqualTo(1);
    }

    @Test
    void boardEligibilityIsOneRuleForEveryBoard() {
        Long hidden = user("HIDDEN", schoolA, "STUDENT");
        Long privateProfile = user("PRIVATE", schoolA, "STUDENT");
        Long unverified = user("UNVERIFIED", schoolA, "STUDENT");
        Long disabled = user("DISABLED", schoolA, "STUDENT");
        Long pending = user("PENDING", null, "STUDENT");
        for (Long id : List.of(hidden, privateProfile, unverified, disabled, pending)) {
            account(id);
        }
        jdbc.update("update user_privacy set show_on_ranking = false where user_id = ?", hidden);
        jdbc.update("update user_privacy set profile_visibility = 'PRIVATE' where user_id = ?", privateProfile);
        jdbc.update("update platform_account set ownership_status = 'RESOLVED' where user_id = ?", unverified);
        jdbc.update("update app_user set status = 'DISABLED' where id = ?", disabled);
        jdbc.update("insert into school_member (school_id, user_id, status) values (?, ?, 'PENDING')", schoolA, pending);

        List<Long> eligible = jdbc.queryForList("select user_id from school_board_account where school_id = ?",
                Long.class, schoolA);
        assertThat(eligible).contains(a1, a2)
                .doesNotContain(hidden, privateProfile, unverified, disabled, pending, b1);
        // 每人至多一所已通过的学校
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')", schoolB, a1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
