package com.szpu.algohub.problem;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 练习推荐的口径验证：
 * - 只在已同步题库里挑题，题库为空时明确说明而不是硬凑；
 * - 不推荐已经通过的题；
 * - 难度区间由最近通过题目的原始难度推出，只在同平台内比较；
 * - 标签分类筛选生效；
 * - 弱项分类按「已通过 / 题库题量」比例排序。
 */
@SpringBootTest
class RecommendationServiceTest {

    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'rec-test%'");

        AppUser user = new AppUser();
        user.setUsername("rec-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("推荐测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("rec-handle");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        accountId = account.getId();
    }

    /** 直接写入题库行，模拟题库同步的结果。 */
    private Long catalogProblem(String key, int rating, int solvedCount, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values ('CODEFORCES', ?, ?, ?, ?, ?, 'OFFICIAL', ?, ?, true, now(), now())
                """, key, "题目 " + key, "https://codeforces.com/problemset/problem/" + key,
                String.valueOf(rating), "CF " + rating, rating, solvedCount);
        Long id = jdbcTemplate.queryForObject(
                "select id from problem where platform='CODEFORCES' and problem_key=?", Long.class, key);
        for (String tag : tags) {
            jdbcTemplate.update("""
                    insert into problem_tag (problem_id, tag_raw, tag_source) values (?, ?, 'OFFICIAL')
                    on conflict do nothing
                    """, id, tag);
        }
        return id;
    }

    private void markSolved(Long problemId) {
        solvedMapper.upsert(userId, accountId, problemId, Platform.CODEFORCES.name(),
                Instant.now(), "EXACT", "SYNC");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    @Test
    void emptyCatalogIsReportedInsteadOfGuessing() {
        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 5);

        assertThat(result.get("catalogSize")).isEqualTo(0);
        assertThat(items(result)).isEmpty();
        assertThat(String.valueOf(result.get("note"))).contains("题库还没有同步");
    }

    @Test
    void withoutDifficultyHistoryUsesAnExplicitBeginnerBand() {
        catalogProblem("1000A", 900, 5000, List.of("implementation"));
        catalogProblem("1000B", 1600, 4000, List.of("dp"));

        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 5);

        assertThat(String.valueOf(result.get("levelBasis"))).contains("没有带难度的通过记录");
        assertThat(items(result)).extracting(r -> r.get("problemKey")).containsExactly("1000A");
    }

    @Test
    void recommendsUnsolvedProblemsSlightlyAboveCurrentLevel() {
        Long solved1 = catalogProblem("2000A", 1200, 9000, List.of("greedy"));
        Long solved2 = catalogProblem("2000B", 1400, 8000, List.of("dp"));
        catalogProblem("2000C", 1500, 7000, List.of("dp"));
        catalogProblem("2000D", 1600, 6000, List.of("graphs"));
        catalogProblem("2000E", 2600, 100, List.of("flows"));
        markSolved(solved1);
        markSolved(solved2);

        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 10);

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) result.get("difficultyRange");
        assertThat((Integer) range.get("min")).isEqualTo(1400);
        assertThat((Integer) range.get("max")).isEqualTo(1700);

        List<Object> keys = items(result).stream().map(r -> r.get("problemKey")).toList();
        // 已通过的不再推荐，区间外的高难度题也不出现
        assertThat(keys).containsExactlyInAnyOrder("2000C", "2000D");
        assertThat(keys).doesNotContain("2000A", "2000B", "2000E");
    }

    @Test
    void recommendationCarriesLinkDifficultyAndTags() {
        Long solved = catalogProblem("3000A", 1200, 9000, List.of("math"));
        markSolved(solved);
        catalogProblem("3000B", 1300, 8800, List.of("dp", "greedy"));

        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 5);
        Map<String, Object> item = items(result).get(0);

        assertThat(String.valueOf(item.get("url"))).startsWith("https://codeforces.com/");
        assertThat(item.get("difficultyLabel")).isEqualTo("CF 1300");
        assertThat(item.get("difficultySource")).isEqualTo("OFFICIAL");
        assertThat(item.get("solvedCount")).isEqualTo(8800);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) item.get("tags");
        assertThat(tags).extracting(t -> t.get("tagRaw")).containsExactlyInAnyOrder("dp", "greedy");
        assertThat(tags).extracting(t -> t.get("category")).contains("动态规划", "贪心");
    }

    @Test
    void categoryFilterOnlyReturnsMatchingProblems() {
        Long solved = catalogProblem("4000A", 1200, 9000, List.of("math"));
        markSolved(solved);
        catalogProblem("4000B", 1300, 8000, List.of("dp"));
        catalogProblem("4000C", 1300, 7000, List.of("strings"));

        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", "动态规划", 10);

        assertThat(items(result)).extracting(r -> r.get("problemKey")).containsExactly("4000B");
        assertThat(result.get("category")).isEqualTo("动态规划");
    }

    @Test
    void emptyBandGivesActionableHintInsteadOfEmptySilence() {
        Long solved = catalogProblem("5000A", 1200, 9000, List.of("math"));
        markSolved(solved);
        catalogProblem("5000B", 2800, 50, List.of("flows"));

        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 5);

        assertThat(items(result)).isEmpty();
        assertThat(String.valueOf(result.get("note"))).contains("换个标签分类");
    }

    @Test
    void noteNeverClaimsAbilityAssessment() {
        catalogProblem("6000A", 1000, 100, List.of("implementation"));
        Map<String, Object> result = recommendationService.recommend(userId, "CODEFORCES", null, 5);
        assertThat(String.valueOf(result.get("note"))).contains("不是能力评估");
    }

    @Test
    void weakCategoriesRankLeastPracticedFirst() {
        // 动态规划：题库 25 道，做了 1 道；字符串：题库 25 道，做了 10 道
        for (int i = 0; i < 25; i++) {
            Long dp = catalogProblem("dp" + i, 1300, 100, List.of("dp"));
            if (i == 0) {
                markSolved(dp);
            }
            Long str = catalogProblem("str" + i, 1300, 100, List.of("strings"));
            if (i < 10) {
                markSolved(str);
            }
        }

        Map<String, Object> result = recommendationService.weakCategories(userId, "CODEFORCES", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("category")).isEqualTo("动态规划");
        assertThat(String.valueOf(result.get("note"))).contains("比例低不代表一定是弱项");
    }

    @Test
    void weakCategoriesRequireSyncedCatalog() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> recommendationService.weakCategories(userId, "CODEFORCES", 5))
                .isInstanceOf(com.szpu.algohub.common.ApiException.class)
                .hasMessageContaining("题库尚未同步");
    }
}
