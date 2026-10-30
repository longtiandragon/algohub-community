package com.szpu.algohub.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.problem.ProblemService;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 校内刷题动态的口径验证：
 * - 动态条目来自“已知最早通过时间”，没有时间的记录不出现；
 * - 排除关闭排行榜展示与档案仅自己可见的同学；
 * - 游标翻页不重不漏。
 *
 * 个人提交记录与导出由 /api/history 负责（见 HistoryApiTest），这里不重复覆盖。
 */
@SpringBootTest
class ActivityServiceTest {

    @Autowired
    private ActivityService activityService;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;
    private Long userB;
    private Long accountA;
    private Long accountB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'feed-test%'");

        userA = newUser("feed-test-a-" + System.nanoTime(), "同学甲");
        userB = newUser("feed-test-b-" + System.nanoTime(), "同学乙");
        accountA = newAccount(userA, Platform.CODEFORCES, "VERIFIED", "PUBLIC");
        accountB = newAccount(userB, Platform.ATCODER, "VERIFIED", "COOKIE");
    }

    private Long newUser(String username, String displayName) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(displayName);
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        jdbcTemplate.update("insert into user_privacy (user_id) values (?) on conflict do nothing", user.getId());
        // 校内动态只看本校：测试同学都加入同一所学校
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) "
                + "select id, ?, 'APPROVED' from school order by id limit 1", user.getId());
        return user.getId();
    }

    private Long newAccount(Long userId, Platform platform, String ownership, String authMode) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(platform.name());
        account.setPlatformUid(platform.name() + "-" + System.nanoTime());
        account.setHandle("handle-" + platform.name().toLowerCase());
        account.setOwnershipStatus(ownership);
        account.setAuthMode(authMode);
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long solve(Long userId, Long accountId, Platform platform, String key, String title,
                       Instant acAt, String confidence, List<String> tags) {
        Long problemId = problemService.upsert(platform,
                new FetchedProblem(key, title, "https://example.invalid/" + key,
                        "1500", "CF 1500", "OFFICIAL", tags, true));
        solvedMapper.upsert(userId, accountId, problemId, platform.name(), acAt, confidence, "SYNC");
        return problemId;
    }

    @Test
    void feedListsSolvesNewestFirstWithProblemLink() {
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "4A", "Watermelon", now.minus(2, ChronoUnit.HOURS),
                "EXACT", List.of("math"));
        solve(userB, accountB, Platform.ATCODER, "abc300_a", "Award", now.minus(1, ChronoUnit.HOURS),
                "EXACT", List.of());

        var page = activityService.feed(userA, null, null, null, 20);

        assertThat(page.items()).hasSize(2);
        Map<String, Object> first = page.items().get(0);
        assertThat(first.get("displayName")).isEqualTo("同学乙");
        assertThat(first.get("problemKey")).isEqualTo("abc300_a");
        // 必须带题目链接
        assertThat(String.valueOf(first.get("url"))).startsWith("https://");
        assertThat(String.valueOf(first.get("summary"))).contains("通过了");
        assertThat(first.get("timeAccuracy")).isEqualTo("首次通过时间准确");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void recordsWithoutKnownTimeNeverAppear() {
        solve(userA, accountA, Platform.CODEFORCES, "1A", "Theatre Square", null, "UNKNOWN", List.of());
        solve(userA, accountA, Platform.CODEFORCES, "2A", "Winner", Instant.now(), "EXACT", List.of());

        var page = activityService.feed(userA, null, null, null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).get("problemKey")).isEqualTo("2A");
        var coverage=activityService.ownCoverage(userA).stream().filter(r->"CODEFORCES".equals(r.get("platform"))).findFirst().orElseThrow();
        assertThat(((Number)coverage.get("solvedCount")).longValue()).isEqualTo(2);
        assertThat(((Number)coverage.get("untimedCount")).longValue()).isEqualTo(1);
        assertThat(activityService.ownCoverage(userB)).allSatisfy(row->assertThat(((Number)row.get("solvedCount")).longValue()).isZero());
    }

    @Test
    void earliestKnownTimeIsLabelledAsNotExact() {
        solve(userA, accountA, Platform.CODEFORCES, "3A", "Shortest path", Instant.now(),
                "EARLIEST_KNOWN", List.of());

        var page = activityService.feed(userA, null, null, null, 20);

        assertThat(page.items().get(0).get("timeAccuracy"))
                .isEqualTo("当前已知最早通过时间，历史可能未回填完整");
    }

    @Test
    void recentOnlyPlatformSaysLatestAcceptedNotBackfill() {
        // 力扣只公开最近通过的题：时间是最近一次通过，永远补不全，不能写"可能未回填"
        Long leetcode = newAccount(userA, Platform.LEETCODE_CN, "VERIFIED", "PUBLIC");
        solve(userA, leetcode, Platform.LEETCODE_CN, "two-sum", "1. 两数之和", Instant.now(), "EARLIEST_KNOWN", List.of());

        var page = activityService.feed(userA, null, null, null, 20);

        assertThat(page.items().get(0).get("timeAccuracy"))
                .isEqualTo("这是最近一次通过的时间，不一定是第一次：该平台只公开最近通过的题");
        assertThat(page.items().get(0).get("timeTag")).isEqualTo("最近一次通过");
    }

    @Test
    void optingOutOfRankingAlsoHidesFromSchoolFeed() {
        solve(userB, accountB, Platform.ATCODER, "abc301_a", "Overall Winner", Instant.now(),
                "EXACT", List.of());
        assertThat(activityService.feed(userA, null, null, null, 20).items()).hasSize(1);

        authService.updatePrivacy(userB, new PrivacyRequest(null, false, null, null, null),
                "127.0.0.1", "JUnit");
        assertThat(activityService.feed(userA, null, null, null, 20).items()).isEmpty();

        // 个人的完整记录改由「我的档案 → 刷题历史与导出」（/api/history）提供，校内动态只管公开展示
    }

    @Test
    void privateProfileIsExcludedFromSchoolFeed() {
        solve(userB, accountB, Platform.ATCODER, "abc302_a", "Attack", Instant.now(), "EXACT", List.of());
        authService.updatePrivacy(userB, new PrivacyRequest("PRIVATE", null, null, null, null),
                "127.0.0.1", "JUnit");

        assertThat(activityService.feed(userA, null, null, null, 20).items()).isEmpty();
    }

    @Test
    void cursorPaginationDoesNotSkipOrRepeat() {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            solve(userA, accountA, Platform.CODEFORCES, "P" + i, "题目 " + i,
                    base.plusSeconds(i * 60L), "EXACT", List.of());
        }

        var first = activityService.feed(userA, null, null, null, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();

        var second = activityService.feed(userA, null, null, first.nextCursor(), 2);
        var third = activityService.feed(userA, null, null, second.nextCursor(), 2);

        List<Object> keys = new java.util.ArrayList<>();
        first.items().forEach(r -> keys.add(r.get("problemKey")));
        second.items().forEach(r -> keys.add(r.get("problemKey")));
        third.items().forEach(r -> keys.add(r.get("problemKey")));
        assertThat(keys).containsExactly("P4", "P3", "P2", "P1", "P0");
        assertThat(third.hasMore()).isFalse();
    }

    @Test
    void invalidCursorIsRejected() {
        assertThatThrownBy(() -> activityService.feed(userA, null, null, "not-a-cursor", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("分页游标无效");
    }

    @Test
    void platformFilterApplies() {
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "6A", "Cifera", now, "EXACT", List.of());
        Long atcoderAccount = newAccount(userA, Platform.ATCODER, "VERIFIED", "PUBLIC");
        solve(userA, atcoderAccount, Platform.ATCODER, "abc303_a", "Similar", now, "EXACT", List.of());

        var filtered = activityService.feed(userA, "ATCODER", null, null, 20);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).get("platform")).isEqualTo("ATCODER");
    }

    /* ---------- 「同届」筛选：按学校成员登记的入学年级 ---------- */

    private void setYear(Long userId, int year) {
        jdbcTemplate.update("update school_member set enrollment_year = ? where user_id = ?", year, userId);
    }

    private static long count(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void yearFilterKeepsOnlyTheSameCohort() {
        setYear(userA, 2026);
        setYear(userB, 2025);
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "7A", "Kitahara", now.minus(1, ChronoUnit.HOURS), "EXACT", List.of());
        solve(userB, accountB, Platform.ATCODER, "abc304_a", "First Player", now.minus(2, ChronoUnit.HOURS), "EXACT", List.of());

        assertThat(activityService.feed(userA, null, null, 2026, null, 20).items())
                .extracting(r -> r.get("displayName")).containsExactly("同学甲");
        assertThat(activityService.feed(userA, null, null, 2025, null, 20).items())
                .extracting(r -> r.get("displayName")).containsExactly("同学乙");
        // 不传入学年级：和原来一样看全校（新旧两种调用都一样）
        assertThat(activityService.feed(userA, null, null, null, null, 20).items()).hasSize(2);
        assertThat(activityService.feed(userA, null, null, null, 20).items()).hasSize(2);
        // 这一届没有同学：空列表，不报错
        assertThat(activityService.feed(userA, null, null, 2019, null, 20).items()).isEmpty();
        // 和平台筛选一起用
        assertThat(activityService.feed(userA, "ATCODER", null, 2026, null, 20).items()).isEmpty();
    }

    @Test
    void yearFilterPagesWithCursorWithoutOtherCohorts() {
        setYear(userA, 2026);
        setYear(userB, 2025);
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        for (int i = 0; i < 3; i++) {
            solve(userA, accountA, Platform.CODEFORCES, "Y" + i, "同届 " + i, base.plusSeconds(i * 120L), "EXACT", List.of());
            solve(userB, accountB, Platform.ATCODER, "abc31" + i + "_a", "别届 " + i, base.plusSeconds(i * 120L + 60), "EXACT", List.of());
        }

        var first = activityService.feed(userA, null, null, 2026, null, 2);
        var second = activityService.feed(userA, null, null, 2026, first.nextCursor(), 2);

        assertThat(first.hasMore()).isTrue();
        assertThat(second.hasMore()).isFalse();
        List<Object> keys = new java.util.ArrayList<>();
        first.items().forEach(r -> keys.add(r.get("problemKey")));
        second.items().forEach(r -> keys.add(r.get("problemKey")));
        assertThat(keys).containsExactly("Y2", "Y1", "Y0");
    }

    @Test
    void summaryListsEveryPlatformInTheFeedAndThePlatformsWithoutTimes() {
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "10A", "Soft Drinking", now.minus(1, ChronoUnit.HOURS), "EXACT", List.of());
        // 很早以前的牛客：动态第一页看不到，但统计里要有
        Long nowcoder = newAccount(userA, Platform.NOWCODER, "VERIFIED", "PUBLIC");
        solve(userA, nowcoder, Platform.NOWCODER, "NC1", "早年的题", now.minus(200, ChronoUnit.DAYS), "EXACT", List.of());
        // 洛谷只有通过、没有时间：不进动态，归到「查不到时间」
        Long luogu = newAccount(userB, Platform.LUOGU, "VERIFIED", "PUBLIC");
        solve(userB, luogu, Platform.LUOGU, "P1000", "超级玛丽游戏", null, "UNKNOWN", List.of());

        Map<String, Object> summary = activityService.feedSummary(userA);
        @SuppressWarnings("unchecked")
        var platforms = (List<Map<String, Object>>) summary.get("platforms");
        assertThat(platforms).extracting(row -> row.get("platform")).containsExactly("CODEFORCES", "NOWCODER");
        assertThat(platforms).allSatisfy(row -> assertThat(count(row.get("events"))).isEqualTo(1));
        // 同学乙的 AtCoder 一题没做：不算「查不到时间」
        assertThat(summary.get("untimedPlatforms")).isEqualTo(List.of("LUOGU"));
    }

    @Test
    void summaryCountsOnlyTheSameCohort() {
        setYear(userA, 2026);
        setYear(userB, 2025);
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "10A", "Soft Drinking", now.minus(1, ChronoUnit.HOURS), "EXACT", List.of());
        solve(userA, accountA, Platform.CODEFORCES, "11A", "Increasing Sequence", now.minus(2, ChronoUnit.HOURS), "EXACT", List.of());
        solve(userB, accountB, Platform.ATCODER, "abc306_a", "Echo", now.minus(3, ChronoUnit.HOURS), "EXACT", List.of());

        Map<String, Object> school = activityService.feedSummary(userA);
        assertThat(count(school.get("activeUsers"))).isEqualTo(2);
        assertThat(count(school.get("totalEvents"))).isEqualTo(3);

        Map<String, Object> cohort = activityService.feedSummary(userA, 2026);
        assertThat(count(cohort.get("activeUsers"))).isEqualTo(1);
        assertThat(count(cohort.get("totalEvents"))).isEqualTo(2);
        assertThat(cohort.get("year")).isEqualTo(2026);
        assertThat(String.valueOf(cohort.get("note"))).contains("2026 级");
    }

    @Test
    void yearFilterStillHidesOtherSchoolsAndOptedOutClassmates() {
        setYear(userA, 2026);
        setYear(userB, 2026);
        long tag = System.nanoTime();
        Long otherSchool = jdbcTemplate.queryForObject(
                "insert into school (name, invite_code) values (?, ?) returning id", Long.class, "别的学校-" + tag, "feed-" + tag);
        try {
            // 别校同一届的同学
            Long userC = newUser("feed-test-c-" + tag, "别校同学");
            jdbcTemplate.update("update school_member set school_id = ?, enrollment_year = 2026 where user_id = ?", otherSchool, userC);
            Long accountC = newAccount(userC, Platform.CODEFORCES, "VERIFIED", "PUBLIC");
            Instant now = Instant.now();
            solve(userC, accountC, Platform.CODEFORCES, "12A", "Super Agent", now.minus(1, ChronoUnit.HOURS), "EXACT", List.of());
            // 本校同一届、但关闭了排行展示的同学
            solve(userB, accountB, Platform.ATCODER, "abc307_a", "Weekly Records", now.minus(2, ChronoUnit.HOURS), "EXACT", List.of());
            authService.updatePrivacy(userB, new PrivacyRequest(null, false, null, null, null), "127.0.0.1", "JUnit");
            solve(userA, accountA, Platform.CODEFORCES, "13A", "Numbers", now.minus(3, ChronoUnit.HOURS), "EXACT", List.of());

            assertThat(activityService.feed(userA, null, null, 2026, null, 20).items())
                    .extracting(r -> r.get("displayName")).containsExactly("同学甲");
            assertThat(count(activityService.feedSummary(userA, 2026).get("activeUsers"))).isEqualTo(1);
        } finally {
            jdbcTemplate.update("delete from school_member where school_id = ?", otherSchool);
            jdbcTemplate.update("delete from school where id = ?", otherSchool);
        }
    }

    @Test
    void streakCountsConsecutiveCampusDays() {
        LocalDate today = TimeSupport.today();
        for (int i = 0; i < 3; i++) {
            solve(userA, accountA, Platform.CODEFORCES, "S" + i, "连续 " + i,
                    TimeSupport.campusDayStart(today.minusDays(i)).plusSeconds(9 * 3600), "EXACT", List.of());
        }
        // 中间断一天，再往前两天
        solve(userA, accountA, Platform.CODEFORCES, "S9", "更早",
                TimeSupport.campusDayStart(today.minusDays(5)).plusSeconds(9 * 3600), "EXACT", List.of());

        Map<String, Object> streak = activityService.streak(userA);

        assertThat(streak.get("currentStreak")).isEqualTo(3);
        assertThat(streak.get("activeDays")).isEqualTo(4);
        assertThat(streak.get("solvedToday")).isEqualTo(1);
        assertThat(String.valueOf(streak.get("note"))).contains("Asia/Shanghai");
    }

    @Test
    void streakIsZeroWhenNothingSolved() {
        Map<String, Object> streak = activityService.streak(userB);
        assertThat(streak.get("currentStreak")).isEqualTo(0);
        assertThat(streak.get("longestStreak")).isEqualTo(0);
    }

    @Test
    void publicScopeMultiYearAndOptOutKeepSchoolParticipation() {
        Instant now = Instant.now();
        solve(userA, accountA, Platform.CODEFORCES, "public-a", "A", now, "EXACT", List.of());
        solve(userB, accountB, Platform.ATCODER, "public-b", "B", now.minusSeconds(1), "EXACT", List.of());
        jdbcTemplate.update("update school_member set enrollment_year=2024 where user_id=?", userA);
        jdbcTemplate.update("update school_member set enrollment_year=2025 where user_id=?", userB);
        assertThat(activityService.feed(userA, null, null, "PUBLIC", List.of(2024,2025), null, 20).items()).hasSize(2);
        assertThat(activityService.feed(userA, null, null, "PUBLIC", List.of(2025), null, 20).items()).extracting(row -> row.get("userId")).containsExactly(userB);
        assertThat(count(activityService.feedSummary(userA, "PUBLIC", List.of(2024,2025)).get("totalEvents"))).isEqualTo(2);
        var first = activityService.feed(userA, null, null, "PUBLIC", null, null, 1);
        assertThat(first.hasMore()).isTrue();
        assertThat(activityService.feed(userA, null, null, "PUBLIC", null, first.nextCursor(), 1).items()).extracting(row -> row.get("userId")).containsExactly(userB);
        authService.updatePrivacy(userB, new PrivacyRequest(null,null,null,null,null,false), "127.0.0.1", "JUnit");
        assertThat(authService.me(userB).privacy().publicActivity()).isFalse();
        assertThat(activityService.feed(userA, null, null, "PUBLIC", null, null, 20).items()).hasSize(1);
        assertThat(activityService.feed(userA, null, null, "SCHOOL", List.of(2024,2025), null, 20).items()).hasSize(2);
        assertThat(count(activityService.feedSummary(userA, "PUBLIC", null).get("totalEvents"))).isEqualTo(1);
        authService.updatePrivacy(userB, new PrivacyRequest(null,null,null,null,null,true), "127.0.0.1", "JUnit");
        jdbcTemplate.update("delete from school_member where user_id=?", userB);
        jdbcTemplate.update("update app_user set enrollment_year=2025 where id=?", userB);
        assertThat(activityService.feed(userB, null, null, "SCHOOL", null, null, 20).items()).isEmpty();
        assertThat(activityService.feed(userB, null, null, "PUBLIC", List.of(2024,2025), null, 20).items()).hasSize(2);
        authService.updatePrivacy(userB, new PrivacyRequest("PRIVATE",null,null,null,null), "127.0.0.1", "JUnit");
        assertThat(activityService.feed(userA, null, null, "PUBLIC", null, null, 20).items()).hasSize(1);
    }
}
