package com.szpu.algohub.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.problem.ProblemsetService.Query;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 题库浏览的口径验证：
 * - 只列已同步进题库的题，做过但没进题库的题不冒充题库条目；
 * - 「已通过」来自各自的通过记录，别人的记录不会串到我这里；
 * - 平台、难度区间、算法方向、通过状态、关键词都能筛；
 * - 指定难度区间时显式只看有难度的题，并在说明里讲明；
 * - 排序与分页按参数走，非法参数被拒。
 */
@SpringBootTest
class ProblemsetServiceTest {

    @Autowired
    private ProblemsetService problemsetService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
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
        jdbcTemplate.update("delete from app_user where username like 'pset-test%'");

        userA = newUser("pset-test-a-" + System.nanoTime());
        userB = newUser("pset-test-b-" + System.nanoTime());
        accountA = newAccount(userA);
        accountB = newAccount(userB);
    }

    private Long newUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName("题库测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return user.getId();
    }

    private Long newAccount(Long userId) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("pset-handle");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long problem(String platform, String key, String title, Integer rating, Integer solvedCount,
                         boolean inCatalog, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'OFFICIAL', ?, ?, true, ?, now())
                """, platform, key, title, "https://example.invalid/" + key,
                rating == null ? null : String.valueOf(rating),
                rating == null ? null : "CF " + rating, rating, solvedCount,
                inCatalog ? Instant.now().atZone(java.time.ZoneOffset.UTC).toOffsetDateTime() : null);
        Long id = jdbcTemplate.queryForObject("select id from problem where platform=? and problem_key=?",
                Long.class, platform, key);
        for (String tag : tags) {
            jdbcTemplate.update("""
                    insert into problem_tag (problem_id, tag_raw, tag_source) values (?, ?, 'OFFICIAL')
                    on conflict do nothing
                    """, id, tag);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private Query query(String platform, Integer min, Integer max, String category, String status,
                        String sort) {
        return new Query(platform, min, max, false, null, category, status, sort, 1, 20);
    }

    private List<Object> keys(Map<String, Object> result) {
        return items(result).stream().map(r -> r.get("problemKey")).toList();
    }

    @Test
    void onlyCatalogProblemsAreListed() {
        problem("CODEFORCES", "400A", "题库里的", 1200, 9000, true, List.of("dp"));
        problem("CODEFORCES", "400B", "做过但不在题库", 1200, 0, false, List.of("dp"));

        Map<String, Object> result = problemsetService.search(userA, query(null, null, null, null, null, null));

        assertThat(keys(result)).containsExactly("400A");
        assertThat(result.get("total")).isEqualTo(1L);
        assertThat(String.valueOf(result.get("note"))).contains("不拿同学做过的题冒充题库");
    }

    @Test
    void solvedFlagComesFromMyOwnRecordsOnly() {
        Long id = problem("CODEFORCES", "401A", "甲做过", 1200, 100, true, List.of());
        solvedMapper.upsert(userB, accountB, id, Platform.CODEFORCES.name(), Instant.now(), "EXACT", "SYNC");

        assertThat(items(problemsetService.search(userA, query(null, null, null, null, null, null)))
                .get(0).get("solved")).isEqualTo(false);
        assertThat(items(problemsetService.search(userB, query(null, null, null, null, null, null)))
                .get(0).get("solved")).isEqualTo(true);
    }

    @Test
    void statusFilterSplitsSolvedAndUnsolved() {
        Long solved = problem("CODEFORCES", "402A", "已通过", 1200, 100, true, List.of());
        problem("CODEFORCES", "402B", "未通过", 1300, 100, true, List.of());
        solvedMapper.upsert(userA, accountA, solved, Platform.CODEFORCES.name(), Instant.now(),
                "EXACT", "SYNC");

        assertThat(keys(problemsetService.search(userA, query(null, null, null, null, "SOLVED", null))))
                .containsExactly("402A");
        assertThat(keys(problemsetService.search(userA, query(null, null, null, null, "UNSOLVED", null))))
                .containsExactly("402B");
    }

    @Test
    void difficultyRangeImpliesOnlyRatedAndSaysSo() {
        problem("CODEFORCES", "403A", "有难度", 1400, 100, true, List.of());
        problem("CODEFORCES", "403B", "无难度", null, 100, true, List.of());

        Map<String, Object> ranged = problemsetService.search(userA,
                query(null, 1200, 1600, null, null, null));

        assertThat(keys(ranged)).containsExactly("403A");
        assertThat(ranged.get("onlyRated")).isEqualTo(true);
        assertThat(String.valueOf(ranged.get("note"))).contains("没有难度的题目不在结果里");

        // 不给区间时，未评级的题照样列出来，不被悄悄过滤
        assertThat(keys(problemsetService.search(userA, query(null, null, null, null, null, "KEY"))))
                .containsExactly("403A", "403B");
    }

    @Test
    void categoryFilterUsesTheUnifiedMapping() {
        problem("CODEFORCES", "404A", "动规题", 1300, 100, true, List.of("dp"));
        problem("CODEFORCES", "404B", "贪心题", 1300, 100, true, List.of("greedy"));

        assertThat(keys(problemsetService.search(userA, query(null, null, null, "动态规划", null, null))))
                .containsExactly("404A");
    }

    @Test
    void platformFilterAndSortsWork() {
        problem("CODEFORCES", "405A", "热门", 1600, 9000, true, List.of());
        problem("CODEFORCES", "405B", "冷门", 1200, 10, true, List.of());
        problem("ATCODER", "abc405_a", "别家的", 1000, 5000, true, List.of());

        assertThat(keys(problemsetService.search(userA, query("CODEFORCES", null, null, null, null, null))))
                .containsExactly("405A", "405B");
        assertThat(keys(problemsetService.search(userA,
                query("CODEFORCES", null, null, null, null, "DIFFICULTY_ASC"))))
                .containsExactly("405B", "405A");
        assertThat(keys(problemsetService.search(userA,
                query("CODEFORCES", null, null, null, null, "DIFFICULTY_DESC"))))
                .containsExactly("405A", "405B");
    }

    @Test
    void keywordMatchesBothProblemKeyAndTitle() {
        problem("CODEFORCES", "406A", "Watermelon", 800, 100, true, List.of());
        problem("CODEFORCES", "999Z", "另一道题", 800, 100, true, List.of());

        Query byTitle = new Query(null, null, null, false, "water", null, null, null, 1, 20);
        Query byKey = new Query(null, null, null, false, "406", null, null, null, 1, 20);

        assertThat(keys(problemsetService.search(userA, byTitle))).containsExactly("406A");
        assertThat(keys(problemsetService.search(userA, byKey))).containsExactly("406A");
    }

    @Test
    void catalogOverviewReportsPerPlatformCoverage() {
        Long solved = problem("CODEFORCES", "407A", "甲做过", 1200, 100, true, List.of());
        problem("CODEFORCES", "407B", "没做过", 1200, 100, true, List.of());
        problem("ATCODER", "abc407_a", "别家的", 1000, 100, true, List.of());
        solvedMapper.upsert(userA, accountA, solved, Platform.CODEFORCES.name(), Instant.now(),
                "EXACT", "SYNC");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> catalog =
                (List<Map<String, Object>>) problemsetService.search(userA,
                        query(null, null, null, null, null, null)).get("catalog");

        Map<String, Object> cf = catalog.stream()
                .filter(row -> "CODEFORCES".equals(row.get("platform"))).findFirst().orElseThrow();
        assertThat(((Number) cf.get("catalogCount")).intValue()).isEqualTo(2);
        assertThat(((Number) cf.get("solvedCount")).intValue()).isEqualTo(1);
    }

    @Test
    void keywordWildcardsAreMatchedLiterally() {
        problem("CODEFORCES", "406A", "A_B 问题", 1200, 100, true, List.of());
        problem("CODEFORCES", "406B", "AxB 问题", 1200, 100, true, List.of());
        problem("CODEFORCES", "406C", "百分之百%", 1200, 100, true, List.of());
        problem("CODEFORCES", "406D", "反斜杠\\题", 1200, 100, true, List.of());
        assertThat(keys(problemsetService.search(userA,
                new Query(null, null, null, false, "A_B", null, null, "KEY", 1, 20)))).containsExactly("406A");
        assertThat(keys(problemsetService.search(userA,
                new Query(null, null, null, false, "%", null, null, "KEY", 1, 20)))).containsExactly("406C");
        assertThat(keys(problemsetService.search(userA,
                new Query(null, null, null, false, "_", null, null, "KEY", 1, 20)))).containsExactly("406A");
        assertThat(keys(problemsetService.search(userA,
                new Query(null, null, null, false, "\\", null, null, "KEY", 1, 20)))).containsExactly("406D");
    }

    @Test
    void paginationIsBoundedAndInvalidInputIsRejected() {
        assertThatThrownBy(() -> problemsetService.search(userA,
                new Query(null, null, null, false, null, null, null, null, 0, 20)))
                .isInstanceOf(ApiException.class).hasMessageContaining("页码");
        assertThatThrownBy(() -> problemsetService.search(userA,
                new Query(null, null, null, false, null, null, null, null, 1, 999)))
                .isInstanceOf(ApiException.class).hasMessageContaining("单页条数");
        assertThatThrownBy(() -> problemsetService.search(userA,
                new Query(null, 1600, 1200, false, null, null, null, null, 1, 20)))
                .isInstanceOf(ApiException.class).hasMessageContaining("难度下限");
        assertThatThrownBy(() -> problemsetService.search(userA,
                new Query(null, null, null, false, null, null, "MASTERED", null, 1, 20)))
                .isInstanceOf(ApiException.class).hasMessageContaining("状态只支持");
        assertThatThrownBy(() -> problemsetService.search(userA,
                new Query(null, null, null, false, null, null, null, "RANDOM", 1, 20)))
                .isInstanceOf(ApiException.class).hasMessageContaining("排序只支持");
    }
}
