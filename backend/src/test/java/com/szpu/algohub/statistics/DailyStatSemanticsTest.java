package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.problem.ProblemService;
import com.szpu.algohub.sync.SyncService;

/**
 * 验证技术路线 5.1 的口径示例：
 * 昨天通过 A；今天再次通过 A 两次、首次通过 B 一次
 * → 今天 新增通过 = 1，当日去重通过 = 2，提交次数 = 3。
 */
@SpringBootTest
class DailyStatSemanticsTest {

    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private SubmissionMapper submissionMapper;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private SyncService syncService;
    @Autowired
    private DailyStatMapper dailyStatMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'stat-test%'");

        AppUser user = new AppUser();
        user.setUsername("stat-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("统计测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("stat-test");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("IDLE");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        accountId = account.getId();
    }

    private Long problem(String key) {
        return problemService.upsert(Platform.CODEFORCES,
                new FetchedProblem(key, "题目 " + key, "https://example.invalid/" + key,
                        "1200", "CF 1200", "OFFICIAL", List.of("dp"), true));
    }

    private void submit(String id, Long problemId, Instant at, boolean accepted) {
        submissionMapper.upsert(accountId, problemId, Platform.CODEFORCES.name(), id,
                accepted ? "OK" : "WRONG_ANSWER", accepted, at, "EXACT", "C++", null);
        if (accepted) {
            solvedMapper.upsert(userId, accountId, problemId, Platform.CODEFORCES.name(), at,
                    "EARLIEST_KNOWN", "SYNC");
        }
    }

    @Test
    void dailyMetricsFollowDocumentedDefinitions() {
        LocalDate today = TimeSupport.today();
        Instant yesterdayNoon = TimeSupport.campusDayStart(today.minusDays(1)).plusSeconds(12 * 3600);
        Instant todayMorning = TimeSupport.campusDayStart(today).plusSeconds(9 * 3600);

        Long problemA = problem("100A");
        Long problemB = problem("100B");

        submit("1", problemA, yesterdayNoon, true);
        submit("2", problemA, todayMorning, true);
        submit("3", problemA, todayMorning.plusSeconds(600), true);
        submit("4", problemB, todayMorning.plusSeconds(1200), true);

        // 历史完整时，最早已知通过时间才视为首次 AC
        solvedMapper.promoteConfidence(accountId);
        syncService.rebuildSubmissionStats(accountId);

        assertThat(metric(today, "FIRST_AC")).isEqualTo(1);
        assertThat(metric(today, "UNIQUE_AC")).isEqualTo(2);
        assertThat(metric(today, "SUBMISSIONS")).isEqualTo(3);
        assertThat(metric(today, "AC_SUBMISSIONS")).isEqualTo(3);
        assertThat(metric(today.minusDays(1), "FIRST_AC")).isEqualTo(1);
    }

    @Test
    void repeatedIngestDoesNotDoubleCount() {
        LocalDate today = TimeSupport.today();
        Instant at = TimeSupport.campusDayStart(today).plusSeconds(10 * 3600);
        Long problemA = problem("200A");

        submit("900", problemA, at, true);
        submit("900", problemA, at, true);
        syncService.rebuildSubmissionStats(accountId);

        assertThat(submissionMapper.countByAccount(accountId)).isEqualTo(1);
        assertThat(metric(today, "SUBMISSIONS")).isEqualTo(1);
        assertThat(metric(today, "UNIQUE_AC")).isEqualTo(1);
    }

    @Test
    void unknownFirstAcIsNotCountedAsFirstAc() {
        LocalDate today = TimeSupport.today();
        Instant at = TimeSupport.campusDayStart(today).plusSeconds(8 * 3600);
        Long problemA = problem("300A");

        submit("901", problemA, at, true);
        // 历史未完整回填：首次 AC 只能标为“已知最早”，不计入 FIRST_AC
        solvedMapper.downgradeConfidence(accountId);
        syncService.rebuildSubmissionStats(accountId);

        assertThat(metric(today, "FIRST_AC")).isZero();
        assertThat(metric(today, "UNIQUE_AC")).isEqualTo(1);
    }

    @Test
    void failedSubmissionsCountOnlyInSubmissionMetric() {
        LocalDate today = TimeSupport.today();
        Instant at = TimeSupport.campusDayStart(today).plusSeconds(11 * 3600);
        Long problemA = problem("400A");

        submit("902", problemA, at, false);
        submit("903", problemA, at.plusSeconds(60), true);
        solvedMapper.promoteConfidence(accountId);
        syncService.rebuildSubmissionStats(accountId);

        assertThat(metric(today, "SUBMISSIONS")).isEqualTo(2);
        assertThat(metric(today, "AC_SUBMISSIONS")).isEqualTo(1);
        assertThat(metric(today, "UNIQUE_AC")).isEqualTo(1);
        assertThat(metric(today, "FIRST_AC")).isEqualTo(1);
    }

    private int metric(LocalDate date, String metric) {
        List<Map<String, Object>> rows = dailyStatMapper.heatmap(userId, date, date, null);
        return rows.stream()
                .filter(row -> metric.equals(row.get("metric")))
                .mapToInt(row -> ((Number) row.get("value")).intValue())
                .sum();
    }
}
