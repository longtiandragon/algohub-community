package com.szpu.algohub.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.goal.WeeklyGoalService.SaveRequest;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 周目标的口径验证：
 * - 进度只数「通过时间落在本周」的记录，上周的题不算进本周；
 * - 同一题重复写入只算一次；
 * - 可以只针对某个算法方向或平台统计；
 * - 没有已知通过时间的记录不计入，但单独数出来告诉用户；
 * - 目标是自报的，改目标不会改动任何通过记录。
 */
@SpringBootTest
class WeeklyGoalServiceTest {

    @Autowired
    private WeeklyGoalService goalService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long cfAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'goal-test%'");

        AppUser user = new AppUser();
        user.setUsername("goal-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("目标测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();
        cfAccount = newAccount(Platform.CODEFORCES);
    }

    private Long newAccount(Platform platform) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(platform.name());
        account.setPlatformUid(platform.name() + "-" + System.nanoTime());
        account.setHandle("goal-handle");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long problem(Platform platform, String key, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_label, difficulty_source,
                                     difficulty_value, tags_complete, catalog_synced_at, updated_at)
                values (?, ?, ?, ?, 'CF 1300', 'OFFICIAL', 1300, true, now(), now())
                """, platform.name(), key, "题目 " + key, "https://example.invalid/" + key);
        Long id = jdbcTemplate.queryForObject("select id from problem where platform=? and problem_key=?",
                Long.class, platform.name(), key);
        for (String tag : tags) {
            jdbcTemplate.update("""
                    insert into problem_tag (problem_id, tag_raw, tag_source) values (?, ?, 'OFFICIAL')
                    on conflict do nothing
                    """, id, tag);
        }
        return id;
    }

    /** 本周内的某个时刻：周一中午，落在窗口里且不受运行时刻影响。 */
    private Instant thisWeek() {
        return TimeSupport.campusDayStart(WeeklyGoalService.currentWeekStart()).plusSeconds(12 * 3600);
    }

    private Instant lastWeek() {
        return TimeSupport.campusDayStart(WeeklyGoalService.currentWeekStart().minusWeeks(1))
                .plusSeconds(12 * 3600);
    }

    private void solve(Long accountId, Long problemId, Platform platform, Instant acAt) {
        solvedMapper.upsert(userId, accountId, problemId, platform.name(), acAt,
                acAt == null ? "UNKNOWN" : "EXACT", "SYNC");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    @Test
    void progressCountsOnlyProblemsAcceptedInsideThisWeek() {
        solve(cfAccount, problem(Platform.CODEFORCES, "300A", List.of()), Platform.CODEFORCES, thisWeek());
        solve(cfAccount, problem(Platform.CODEFORCES, "300B", List.of()), Platform.CODEFORCES, thisWeek());
        solve(cfAccount, problem(Platform.CODEFORCES, "300C", List.of()), Platform.CODEFORCES, lastWeek());

        goalService.save(userId, new SaveRequest(5, null, null));
        Map<String, Object> current = goalService.current(userId);

        assertThat(current.get("solved")).isEqualTo(2);
        assertThat(current.get("target")).isEqualTo(5);
        assertThat(current.get("remaining")).isEqualTo(3);
        assertThat(current.get("reached")).isEqualTo(false);
        assertThat(list(current, "recentProblems")).hasSize(2);
        assertThat(String.valueOf(list(current, "recentProblems").get(0).get("url"))).startsWith("https://");
    }

    @Test
    void reachingTheTargetIsReportedAndProgressCanExceedIt() {
        for (String key : List.of("301A", "301B", "301C")) {
            solve(cfAccount, problem(Platform.CODEFORCES, key, List.of()), Platform.CODEFORCES, thisWeek());
        }
        goalService.save(userId, new SaveRequest(2, null, null));

        Map<String, Object> current = goalService.current(userId);

        assertThat(current.get("solved")).isEqualTo(3);
        assertThat(current.get("reached")).isEqualTo(true);
        assertThat(current.get("remaining")).isEqualTo(0);
        assertThat(current.get("percent")).isEqualTo(150.0);
    }

    @Test
    void aGoalCanTargetOneAlgorithmDirection() {
        solve(cfAccount, problem(Platform.CODEFORCES, "302A", List.of("dp")), Platform.CODEFORCES, thisWeek());
        solve(cfAccount, problem(Platform.CODEFORCES, "302B", List.of("greedy")), Platform.CODEFORCES,
                thisWeek());

        goalService.save(userId, new SaveRequest(3, "动态规划", null));
        Map<String, Object> current = goalService.current(userId);

        assertThat(current.get("category")).isEqualTo("动态规划");
        assertThat(current.get("solved")).isEqualTo(1);
        assertThat(String.valueOf(current.get("note"))).contains("只统计「动态规划」方向");
    }

    @Test
    void aGoalCanTargetOnePlatform() {
        Long atcoder = newAccount(Platform.ATCODER);
        solve(cfAccount, problem(Platform.CODEFORCES, "303A", List.of()), Platform.CODEFORCES, thisWeek());
        solve(atcoder, problem(Platform.ATCODER, "abc310_a", List.of()), Platform.ATCODER, thisWeek());

        goalService.save(userId, new SaveRequest(3, null, "ATCODER"));

        assertThat(goalService.current(userId).get("solved")).isEqualTo(1);
    }

    @Test
    void recordsWithoutKnownTimeAreExcludedButReportedSeparately() {
        solve(cfAccount, problem(Platform.CODEFORCES, "304A", List.of()), Platform.CODEFORCES, thisWeek());
        // 源站没给通过时间的记录：不能算进本周，也不能装作没有
        solve(cfAccount, problem(Platform.CODEFORCES, "304B", List.of()), Platform.CODEFORCES, null);

        goalService.save(userId, new SaveRequest(2, null, null));
        Map<String, Object> current = goalService.current(userId);

        assertThat(current.get("solved")).isEqualTo(1);
        assertThat(current.get("addedWithoutTime")).isEqualTo(1);
        assertThat(String.valueOf(current.get("note"))).contains("不计入本周进度，也没有被丢掉");
    }

    @Test
    void settingAGoalTwiceUpdatesTheSameWeekInsteadOfAddingAnother() {
        goalService.save(userId, new SaveRequest(3, null, null));
        goalService.save(userId, new SaveRequest(7, "贪心", null));

        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from weekly_goal where user_id=?", Long.class, userId);
        Map<String, Object> current = goalService.current(userId);

        assertThat(rows).isEqualTo(1);
        assertThat(current.get("target")).isEqualTo(7);
        assertThat(current.get("category")).isEqualTo("贪心");
    }

    @Test
    void changingOrRemovingAGoalNeverTouchesAcceptedRecords() {
        solve(cfAccount, problem(Platform.CODEFORCES, "305A", List.of()), Platform.CODEFORCES, thisWeek());
        Long before = jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem where user_id=?", Long.class, userId);

        goalService.save(userId, new SaveRequest(99, null, null));
        Map<String, Object> afterRemove = goalService.remove(userId);

        assertThat(afterRemove.get("removed")).isEqualTo(true);
        assertThat(afterRemove.get("hasGoal")).isEqualTo(false);
        // 没设目标也照样如实报出本周已通过题数
        assertThat(afterRemove.get("solved")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem where user_id=?", Long.class, userId))
                .isEqualTo(before);
    }

    @Test
    void withoutAGoalTheWeekProgressIsStillReported() {
        solve(cfAccount, problem(Platform.CODEFORCES, "306A", List.of()), Platform.CODEFORCES, thisWeek());

        Map<String, Object> current = goalService.current(userId);

        assertThat(current.get("hasGoal")).isEqualTo(false);
        assertThat(current.get("target")).isNull();
        assertThat(current.get("solved")).isEqualTo(1);
        assertThat(current).doesNotContainKey("reached");
    }

    @Test
    void historyShowsPreviousWeeksAgainstTheirOwnTargets() {
        LocalDate lastWeekStart = WeeklyGoalService.currentWeekStart().minusWeeks(1);
        jdbcTemplate.update("insert into weekly_goal (user_id, week_start, target) values (?, ?, 2)",
                userId, lastWeekStart);
        solve(cfAccount, problem(Platform.CODEFORCES, "307A", List.of()), Platform.CODEFORCES, lastWeek());
        solve(cfAccount, problem(Platform.CODEFORCES, "307B", List.of()), Platform.CODEFORCES, lastWeek());
        solve(cfAccount, problem(Platform.CODEFORCES, "307C", List.of()), Platform.CODEFORCES, thisWeek());

        Map<String, Object> current = goalService.current(userId);
        List<Map<String, Object>> history = list(current, "history");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("target")).isEqualTo(2);
        assertThat(history.get(0).get("solved")).isEqualTo(2);
        assertThat(history.get(0).get("reached")).isEqualTo(true);
        // 上周的题不会算进本周
        assertThat(current.get("solved")).isEqualTo(1);
    }

    @Test
    void invalidTargetsAreRejected() {
        assertThatThrownBy(() -> goalService.save(userId, new SaveRequest(0, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1～500");
        assertThatThrownBy(() -> goalService.save(userId, new SaveRequest(null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请填写");
    }
}
