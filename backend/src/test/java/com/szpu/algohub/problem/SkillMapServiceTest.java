package com.szpu.algohub.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 算法地图的口径验证：
 * - 分类来自统一标签映射，未命中的记 UNMAPPED；
 * - 一题多标签会在多个分类里各计一次（页面上也这么说明）；
 * - 覆盖率的分母是题库里该分类的题量，题库为 0 时不给覆盖率而不是算成 100%；
 * - 没有任何标签的平台如实说明画不出地图，不猜测；
 * - 分类详情里「还没通过」只从已同步题库里挑，且不含已经通过的题。
 */
@SpringBootTest
class SkillMapServiceTest {

    @Autowired
    private SkillMapService skillMapService;
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
        jdbcTemplate.update("delete from app_user where username like 'skill-test%'");

        AppUser user = new AppUser();
        user.setUsername("skill-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("地图测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("skill-handle");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        accountId = account.getId();
    }

    /** 写入一行题库题目，模拟题库同步的结果。catalogSynced=false 表示只是做过、没进题库。 */
    private Long problem(String key, int rating, int solvedCount, boolean catalogSynced, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values ('CODEFORCES', ?, ?, ?, ?, ?, 'OFFICIAL', ?, ?, true, ?, now())
                """, key, "题目 " + key, "https://codeforces.com/problemset/problem/" + key,
                String.valueOf(rating), "CF " + rating, rating, solvedCount,
                catalogSynced ? Instant.now().atZone(java.time.ZoneOffset.UTC).toOffsetDateTime() : null);
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

    private void markSolved(Long problemId, Instant acAt) {
        solvedMapper.upsert(userId, accountId, problemId, Platform.CODEFORCES.name(), acAt, "EXACT", "SYNC");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    private Map<String, Object> category(Map<String, Object> map, String name) {
        return list(map, "categories").stream()
                .filter(row -> name.equals(row.get("category")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有分类 " + name + "：" + list(map, "categories")));
    }

    /** 洛谷题目与账号，用来验证“不传平台时选哪个”。洛谷没有题库，只有通过记录。 */
    private Long luoguSolve(String key, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, tags_complete, updated_at)
                values ('LUOGU', ?, ?, ?, false, now())
                """, key, "洛谷题 " + key, "https://www.luogu.com.cn/problem/" + key);
        Long id = jdbcTemplate.queryForObject(
                "select id from problem where platform='LUOGU' and problem_key=?", Long.class, key);
        for (String tag : tags) {
            jdbcTemplate.update("""
                    insert into problem_tag (problem_id, tag_raw, tag_source) values (?, ?, 'OFFICIAL')
                    on conflict do nothing
                    """, id, tag);
        }
        return id;
    }

    private Long luoguAccount() {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.LUOGU.name());
        account.setPlatformUid("luogu-" + System.nanoTime());
        account.setHandle("skill-luogu");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(false);
        accountMapper.insert(account);
        return account.getId();
    }

    @Test
    void withoutPlatformPicksWhereTheUserActuallyHasTaggedSolves() {
        Long cf = problem("109A", 1300, 100, true, List.of("dp"));
        markSolved(cf, Instant.now());

        Long luogu = luoguAccount();
        for (String key : List.of("P1001", "P1002", "P1003")) {
            solvedMapper.upsert(userId, luogu, luoguSolve(key, List.of("贪心")), Platform.LUOGU.name(),
                    Instant.now(), "EXACT", "SYNC");
        }

        // 洛谷 3 道带标签的通过题 > Codeforces 1 道，默认就该给洛谷，而不是一律 Codeforces
        assertThat(skillMapService.map(userId, null).get("platform")).isEqualTo("LUOGU");
        // 显式指定时仍然听指定的
        assertThat(skillMapService.map(userId, "CODEFORCES").get("platform")).isEqualTo("CODEFORCES");
    }

    @Test
    void withoutAnyTaggedSolvesFallsBackToCodeforces() {
        assertThat(skillMapService.map(userId, null).get("platform")).isEqualTo("CODEFORCES");
    }

    @Test
    void platformWithoutTagsSaysSoInsteadOfDrawingAnEmptyMap() {
        Map<String, Object> map = skillMapService.map(userId, "ATCODER");

        assertThat(list(map, "categories")).isEmpty();
        assertThat(String.valueOf(map.get("note"))).contains("没有可用的题目标签");
        assertThat(String.valueOf(map.get("note"))).contains("不会猜测");
    }

    @Test
    void categoriesComeFromTagMappingAndUnmappedTagsStayUnmapped() {
        Long dp = problem("100A", 1300, 5000, true, List.of("dp"));
        problem("100B", 1400, 4000, true, List.of("dp", "greedy"));
        problem("100C", 1500, 3000, true, List.of("这个标签没有映射"));
        markSolved(dp, Instant.now());

        Map<String, Object> map = skillMapService.map(userId, "CODEFORCES");

        Map<String, Object> dpRow = category(map, "动态规划");
        assertThat(((Number) dpRow.get("catalogCount")).intValue()).isEqualTo(2);
        assertThat(((Number) dpRow.get("solvedCount")).intValue()).isEqualTo(1);
        assertThat(dpRow.get("coverage")).isEqualTo(50.0);

        assertThat(category(map, "UNMAPPED").get("catalogCount")).isEqualTo(1L);
    }

    @Test
    void oneProblemWithTwoTagsCountsInBothCategories() {
        Long both = problem("101A", 1300, 5000, true, List.of("dp", "greedy"));
        markSolved(both, Instant.now());

        Map<String, Object> map = skillMapService.map(userId, "CODEFORCES");

        assertThat(category(map, "动态规划").get("solvedCount")).isEqualTo(1L);
        assertThat(category(map, "贪心").get("solvedCount")).isEqualTo(1L);
        // 只做了一道题，但两个分类各计一次——说明文案必须讲清楚这一点
        assertThat(map.get("solvedTotal")).isEqualTo(1L);
        assertThat(String.valueOf(map.get("note"))).contains("各分类之和大于题目总数");
    }

    @Test
    void coverageIsNullWhenTheCategoryHasNoCatalogProblems() {
        // 做过但没进题库的题：分子有、分母没有，此时不能算出 100%
        Long offCatalog = problem("102A", 1600, 0, false, List.of("graphs"));
        markSolved(offCatalog, Instant.now());

        Map<String, Object> row = category(skillMapService.map(userId, "CODEFORCES"), "图论");

        assertThat(row.get("catalogCount")).isEqualTo(0L);
        assertThat(row.get("solvedCount")).isEqualTo(1L);
        assertThat(row.get("coverage")).isNull();
    }

    @Test
    void coverageCountsOnlySolvedProblemsThatAreInTheCatalog() {
        // Gym 与近期比赛的题目通过了但不在题库里：它们计入通过数，但不能当作覆盖率的分子，
        // 否则分子里混进了分母根本没统计的题，小题库下还会算出超过 100% 的覆盖率。
        Long inCatalog = problem("108A", 1300, 100, true, List.of("dp"));
        Long offCatalog1 = problem("108B", 1300, 100, false, List.of("dp"));
        Long offCatalog2 = problem("108C", 1400, 100, false, List.of("dp"));
        markSolved(inCatalog, Instant.now());
        markSolved(offCatalog1, Instant.now());
        markSolved(offCatalog2, Instant.now());

        Map<String, Object> row = category(skillMapService.map(userId, "CODEFORCES"), "动态规划");

        assertThat(row.get("catalogCount")).isEqualTo(1L);
        assertThat(row.get("solvedCount")).isEqualTo(3L);
        assertThat(row.get("solvedInCatalog")).isEqualTo(1L);
        assertThat(row.get("solvedOffCatalog")).isEqualTo(2L);
        // 3/1 = 300% 是错的；正确答案是题库里的 1/1
        assertThat(row.get("coverage")).isEqualTo(100.0);
    }

    @Test
    void mapReportsHowManySolvedProblemsCarryTagsAtAll() {
        Long tagged = problem("103A", 1300, 100, true, List.of("dp"));
        Long untagged = problem("103B", 1300, 100, true, List.of());
        markSolved(tagged, Instant.now());
        markSolved(untagged, Instant.now());

        Map<String, Object> map = skillMapService.map(userId, "CODEFORCES");

        assertThat(map.get("solvedTotal")).isEqualTo(2L);
        assertThat(map.get("taggedSolved")).isEqualTo(1L);
        assertThat(map.get("untaggedSolved")).isEqualTo(1L);
    }

    @Test
    void categoryDetailListsSolvedProblemsWithLinksNewestFirst() {
        Long older = problem("104A", 1300, 100, true, List.of("dp"));
        Long newer = problem("104B", 1400, 100, true, List.of("dp"));
        markSolved(older, Instant.now().minus(3, ChronoUnit.DAYS));
        markSolved(newer, Instant.now());

        Map<String, Object> detail = skillMapService.category(userId, "CODEFORCES", "动态规划", 20);

        assertThat(list(detail, "solved")).extracting(r -> r.get("problemKey")).containsExactly("104B", "104A");
        assertThat(String.valueOf(list(detail, "solved").get(0).get("url"))).startsWith("https://");
    }

    @Test
    void categoryTodoExcludesSolvedProblemsAndUnsyncedCatalog() {
        Long solved = problem("105A", 1300, 9000, true, List.of("dp"));
        problem("105B", 1400, 8000, true, List.of("dp"));
        problem("105C", 1400, 7000, false, List.of("dp")); // 不在题库里，不该被推荐
        markSolved(solved, Instant.now());

        Map<String, Object> detail = skillMapService.category(userId, "CODEFORCES", "动态规划", 20);

        assertThat(list(detail, "todo")).extracting(r -> r.get("problemKey")).containsExactly("105B");
    }

    @Test
    void categoryTodoFallsBackToAllDifficultiesAndSaysSo() {
        Long solved = problem("106A", 1300, 9000, true, List.of("dp"));
        problem("106B", 2600, 50, true, List.of("dp")); // 远高于当前水平区间
        markSolved(solved, Instant.now());

        Map<String, Object> detail = skillMapService.category(userId, "CODEFORCES", "动态规划", 20);

        assertThat(list(detail, "todo")).extracting(r -> r.get("problemKey")).containsExactly("106B");
        assertThat(String.valueOf(detail.get("todoBasis"))).contains("放宽到该分类的全部难度");
    }

    @Test
    void difficultyHistogramOnlyCountsSolvedProblemsWithKnownDifficulty() {
        Long a = problem("107A", 1300, 100, true, List.of("dp"));
        Long b = problem("107B", 1300, 100, true, List.of("dp"));
        Long c = problem("107C", 1500, 100, true, List.of("dp"));
        markSolved(a, Instant.now());
        markSolved(b, Instant.now());
        markSolved(c, Instant.now());

        List<Map<String, Object>> histogram =
                list(skillMapService.category(userId, "CODEFORCES", "动态规划", 20), "difficultyHistogram");

        assertThat(histogram).hasSize(2);
        assertThat(histogram.get(0).get("difficultyValue")).isEqualTo(1300);
        assertThat(((Number) histogram.get(0).get("count")).intValue()).isEqualTo(2);
    }

    @Test
    void categoryIsRequired() {
        assertThatThrownBy(() -> skillMapService.category(userId, "CODEFORCES", "  ", 20))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请指定算法分类");
    }
}
