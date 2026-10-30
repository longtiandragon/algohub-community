package com.szpu.algohub.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.activity.TrainingActivityService.SaveRequest;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 校内训练活动的口径验证：
 * - 活动开始前就通过的题算「活动前已通过」，不算训练成果；
 * - 通过时间未知的记录两边都不计入，但单独报数；
 * - 未发布的活动学生看不到，管理员看得到；
 * - 活动榜只数活动期间通过的题，并排除关闭校内展示的同学；
 * - 删除活动不动题单与通过记录。
 */
@SpringBootTest
class TrainingActivityServiceTest {

    @Autowired
    private TrainingActivityService activityService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long schoolId;
    private Long admin;
    private Long student;
    private Long other;
    private Long studentAccount;
    private Long otherAccount;
    private Long listId;
    private Long problemA;
    private Long problemB;
    private Long problemC;

    private final Instant start = Instant.now().minus(Duration.ofDays(1));
    private final Instant end = Instant.now().plus(Duration.ofDays(1));

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from training_activity");
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem_list_item, problem restart identity cascade");
        jdbcTemplate.execute("delete from problem_list where code like 'ACT-TEST%'");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'act-test%'");

        schoolId = jdbcTemplate.queryForObject("select id from school order by id limit 1", Long.class);
        admin = newUser("act-test-admin-" + System.nanoTime(), "ADMIN");
        student = newUser("act-test-student-" + System.nanoTime(), "STUDENT");
        other = newUser("act-test-other-" + System.nanoTime(), "STUDENT");
        studentAccount = newAccount(student);
        otherAccount = newAccount(other);

        jdbcTemplate.update("""
                insert into problem_list (code, title, source, visible, share_scope, created_by)
                values ('ACT-TEST-1', '活动题单', 'MANUAL', true, 'PUBLIC', ?)
                """, admin);
        listId = jdbcTemplate.queryForObject("select id from problem_list where code='ACT-TEST-1'",
                Long.class);

        problemA = problem("900A");
        problemB = problem("900B");
        problemC = problem("900C");
        int position = 1;
        for (Long id : List.of(problemA, problemB, problemC)) {
            jdbcTemplate.update(
                    "insert into problem_list_item (list_id, problem_id, position) values (?, ?, ?)",
                    listId, id, position++);
        }
    }

    private Long newUser(String username, String role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(username);
        user.setRole(role);
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        jdbcTemplate.update("insert into user_privacy (user_id) values (?) on conflict do nothing",
                user.getId());
        // 活动榜只列本校同学：测试同学都加入同一所学校
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')",
                schoolId, user.getId());
        return user.getId();
    }

    private Long newAccount(Long userId) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("act-handle");
        account.setOwnershipStatus("VERIFIED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long problem(String key) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_label, difficulty_source,
                                     difficulty_value, tags_complete, catalog_synced_at, updated_at)
                values ('CODEFORCES', ?, ?, ?, 'CF 1300', 'OFFICIAL', 1300, true, now(), now())
                """, key, "题目 " + key, "https://codeforces.com/problemset/problem/" + key);
        return jdbcTemplate.queryForObject(
                "select id from problem where platform='CODEFORCES' and problem_key=?", Long.class, key);
    }

    private void solve(Long userId, Long accountId, Long problemId, Instant acAt) {
        solvedMapper.upsert(userId, accountId, problemId, Platform.CODEFORCES.name(), acAt,
                acAt == null ? "UNKNOWN" : "EXACT", "SYNC");
    }

    private Long createActivity(boolean published) {
        activityService.create(admin, new SaveRequest("周末训练赛", "第一场", listId, start, end, published, "SCHOOL", schoolId));
        return jdbcTemplate.queryForObject("select max(id) from training_activity", Long.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progress(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("progress");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    @Test
    void problemsSolvedBeforeTheActivityAreCountedSeparately() {
        solve(student, studentAccount, problemA, start.minus(Duration.ofDays(30)));
        solve(student, studentAccount, problemB, start.plus(Duration.ofHours(2)));
        Long id = createActivity(true);

        Map<String, Object> detail = activityService.detail(student, id, false);
        Map<String, Object> progress = progress(detail);

        assertThat(((Number) progress.get("beforeCount")).intValue()).isEqualTo(1);
        assertThat(((Number) progress.get("duringCount")).intValue()).isEqualTo(1);
        assertThat(((Number) progress.get("problemCount")).intValue()).isEqualTo(3);
        assertThat(String.valueOf(detail.get("note"))).contains("不算作本次训练的成果");
    }

    @Test
    void recordsWithoutKnownTimeCountAsNeitherButAreReported() {
        solve(student, studentAccount, problemA, null);
        Long id = createActivity(true);

        Map<String, Object> progress = progress(activityService.detail(student, id, false));

        assertThat(((Number) progress.get("duringCount")).intValue()).isZero();
        assertThat(((Number) progress.get("beforeCount")).intValue()).isZero();
        assertThat(((Number) progress.get("unknownTimeCount")).intValue()).isEqualTo(1);
    }

    @Test
    void perProblemStatusLetsProgressBeChecked() {
        solve(student, studentAccount, problemA, start.minus(Duration.ofDays(2)));
        solve(student, studentAccount, problemB, start.plus(Duration.ofHours(1)));
        Long id = createActivity(true);

        List<Map<String, Object>> problems = list(activityService.detail(student, id, false), "problems");

        assertThat(problems).hasSize(3);
        assertThat(problems.get(0).get("status")).isEqualTo("SOLVED_BEFORE");
        assertThat(problems.get(1).get("status")).isEqualTo("SOLVED_DURING");
        assertThat(problems.get(2).get("status")).isEqualTo("TODO");
        assertThat(String.valueOf(problems.get(0).get("url"))).startsWith("https://");
    }

    @Test
    void unpublishedActivitiesAreHiddenFromStudentsButVisibleToAdmins() {
        Long id = createActivity(false);

        assertThat(list(activityService.list(student, false), "items")).isEmpty();
        assertThat(list(activityService.list(admin, true), "items")).hasSize(1);
        assertThatThrownBy(() -> activityService.detail(student, id, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("尚未发布");
        assertThat(activityService.detail(admin, id, true).get("title")).isEqualTo("周末训练赛");
    }

    @Test
    void leaderboardOnlyCountsWorkDoneInsideTheWindow() {
        solve(student, studentAccount, problemA, start.plus(Duration.ofHours(1)));
        solve(student, studentAccount, problemB, start.plus(Duration.ofHours(2)));
        // 另一位同学的两道题都在活动之前完成，不该出现在活动榜上
        solve(other, otherAccount, problemA, start.minus(Duration.ofDays(5)));
        solve(other, otherAccount, problemC, start.minus(Duration.ofDays(4)));
        Long id = createActivity(true);

        List<Map<String, Object>> board = list(activityService.detail(student, id, false), "leaderboard");

        assertThat(board).hasSize(1);
        assertThat(board.get(0).get("userId")).isEqualTo(student);
        assertThat(((Number) board.get(0).get("solvedCount")).intValue()).isEqualTo(2);
        jdbcTemplate.update("update platform_account set ownership_status='RESOLVED' where id=?", studentAccount);
        assertThat(list(activityService.detail(student, id, false), "leaderboard")).isEmpty();
    }

    @Test
    void studentsWhoOptedOutOfSchoolVisibilityStayOffTheLeaderboard() {
        solve(other, otherAccount, problemA, start.plus(Duration.ofHours(1)));
        jdbcTemplate.update("update user_privacy set show_on_ranking = false where user_id = ?", other);
        Long id = createActivity(true);

        assertThat(list(activityService.detail(student, id, false), "leaderboard")).isEmpty();
    }

    @Test
    void stateFollowsTheWindow() {
        activityService.create(admin, new SaveRequest("已结束", null, listId,
                Instant.now().minus(Duration.ofDays(9)), Instant.now().minus(Duration.ofDays(8)), true, "SCHOOL", schoolId));
        activityService.create(admin, new SaveRequest("未开始", null, listId,
                Instant.now().plus(Duration.ofDays(8)), Instant.now().plus(Duration.ofDays(9)), true, "SCHOOL", schoolId));
        createActivity(true);

        Map<String, String> states = new java.util.HashMap<>();
        for (Map<String, Object> item : list(activityService.list(student, false), "items")) {
            states.put(String.valueOf(item.get("title")), String.valueOf(item.get("state")));
        }

        assertThat(states).containsEntry("已结束", "ENDED")
                .containsEntry("未开始", "UPCOMING")
                .containsEntry("周末训练赛", "RUNNING");
    }

    @Test
    void deletingAnActivityTouchesNeitherTheListNorSolveRecords() {
        solve(student, studentAccount, problemA, start.plus(Duration.ofHours(1)));
        Long id = createActivity(true);
        long solvesBefore = jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem", Long.class);

        Map<String, Object> result = activityService.delete(id);

        assertThat(result.get("deleted")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject("select count(*) from problem_list_item where list_id=?",
                Long.class, listId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_solved_problem", Long.class))
                .isEqualTo(solvesBefore);
    }

    @Test
    void invalidActivitiesAreRejected() {
        assertThatThrownBy(() -> activityService.create(admin,
                new SaveRequest("  ", null, listId, start, end, true, "SCHOOL", schoolId)))
                .isInstanceOf(ApiException.class).hasMessageContaining("活动标题");
        assertThatThrownBy(() -> activityService.create(admin,
                new SaveRequest("倒着来", null, listId, end, start, true, "SCHOOL", schoolId)))
                .isInstanceOf(ApiException.class).hasMessageContaining("结束时间必须晚于开始时间");
        assertThatThrownBy(() -> activityService.create(admin,
                new SaveRequest("缺时间", null, listId, null, null, true, "SCHOOL", schoolId)))
                .isInstanceOf(ApiException.class).hasMessageContaining("开始与结束时间");
        assertThatThrownBy(() -> activityService.delete(999999L))
                .isInstanceOf(ApiException.class).hasMessageContaining("活动不存在");
    }

    @Test
    void anActivityWithoutAListSaysSoInsteadOfShowingZeroProgress() {
        activityService.create(admin, new SaveRequest("还没排题", null, null, start, end, true, "SCHOOL", schoolId));
        Long id = jdbcTemplate.queryForObject("select max(id) from training_activity", Long.class);

        Map<String, Object> detail = activityService.detail(student, id, false);

        assertThat(String.valueOf(detail.get("progressNote"))).contains("还没有关联题单");
        assertThat(list(detail, "problems")).isEmpty();
    }
}
