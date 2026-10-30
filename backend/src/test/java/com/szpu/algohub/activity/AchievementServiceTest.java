package com.szpu.algohub.activity;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.szpu.algohub.activity.AchievementService.Achievement;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 成就与每日一题的口径验证：
 * - 成就由已同步数据实时推导，数据回退成就也回退；
 * - 难度成就只按平台原始难度判断，不做跨平台换算；
 * - 每日一题同一天稳定不变、全校相同，题库为空时如实说明。
 */
@SpringBootTest
class AchievementServiceTest {

    @Autowired
    private AchievementService achievementService;
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
        jdbcTemplate.update("delete from app_user where username like 'ach-test%'");

        AppUser user = new AppUser();
        user.setUsername("ach-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("成就测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();
        jdbcTemplate.update("insert into user_privacy (user_id) values (?) on conflict do nothing", userId);
        // 每日一题的校内人数只数本校：测试同学加入学校
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) "
                + "select id, ?, 'APPROVED' from school order by id limit 1", userId);

        accountId = newAccount(Platform.CODEFORCES);
    }

    private Long newAccount(Platform platform) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(platform.name());
        account.setPlatformUid(platform.name() + "-" + System.nanoTime());
        account.setHandle("ach-handle");
        account.setOwnershipStatus("VERIFIED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long catalogProblem(String platform, String key, Integer rating, Integer solvedCount,
                                List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'OFFICIAL', ?, ?, true, now(), now())
                """, platform, key, "题目 " + key, "https://example.invalid/" + key,
                rating == null ? null : String.valueOf(rating),
                rating == null ? null : "CF " + rating, rating, solvedCount);
        Long id = jdbcTemplate.queryForObject(
                "select id from problem where platform=? and problem_key=?", Long.class, platform, key);
        for (String tag : tags) {
            jdbcTemplate.update("""
                    insert into problem_tag (problem_id, tag_raw, tag_source) values (?, ?, 'OFFICIAL')
                    on conflict do nothing
                    """, id, tag);
        }
        return id;
    }

    private void solve(Long problemId, String platform, Long account, Instant at) {
        solvedMapper.upsert(userId, account, problemId, platform, at, "EXACT", "SYNC");
    }

    @SuppressWarnings("unchecked")
    private List<Achievement> list(Map<String, Object> result) {
        return (List<Achievement>) result.get("achievements");
    }

    private Achievement find(Map<String, Object> result, String code) {
        return list(result).stream().filter(a -> a.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void freshUserHasNoUnlockedAchievementsButSeesTargets() {
        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(result.get("unlockedCount")).isEqualTo(0);
        assertThat((Integer) result.get("totalCount")).isGreaterThan(10);
        assertThat(list(result)).allMatch(a -> !a.unlocked());
        assertThat(result.get("nextTarget")).isNotNull();
        assertThat(String.valueOf(result.get("note"))).contains("不单独保存解锁记录");
    }

    @Test
    void solveCountMilestonesUnlockByTier() {
        for (int i = 0; i < 12; i++) {
            solve(catalogProblem("CODEFORCES", "P" + i, 1200, 100, List.of("dp")),
                    "CODEFORCES", accountId, Instant.now().minus(i, ChronoUnit.HOURS));
        }

        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(find(result, "SOLVE_10").unlocked()).isTrue();
        assertThat(find(result, "SOLVE_50").unlocked()).isFalse();
        assertThat(find(result, "SOLVE_50").progress()).isEqualTo(12);
        assertThat(find(result, "SOLVE_50").progressText()).isEqualTo("12 / 50");
    }

    @Test
    void difficultyAchievementUsesPlatformRawRatingOnly() {
        solve(catalogProblem("CODEFORCES", "D1", 1500, 100, List.of()), "CODEFORCES", accountId, Instant.now());

        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(find(result, "DIFFICULTY_1500").unlocked()).isTrue();
        assertThat(find(result, "DIFFICULTY_1800").unlocked()).isFalse();
        assertThat(find(result, "DIFFICULTY_1800").description()).contains("不做跨平台换算");
    }

    @Test
    void unratedProblemsDoNotUnlockDifficultyAchievements() {
        solve(catalogProblem("CODEFORCES", "U1", null, 100, List.of()), "CODEFORCES", accountId, Instant.now());

        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(find(result, "DIFFICULTY_1000").unlocked()).isFalse();
        assertThat(find(result, "DIFFICULTY_1000").progressText()).contains("暂无带难度的通过记录");
    }

    @Test
    void categoryBreadthCountsMappedCategoriesOnly() {
        solve(catalogProblem("CODEFORCES", "C1", 1200, 100, List.of("dp")), "CODEFORCES", accountId, Instant.now());
        solve(catalogProblem("CODEFORCES", "C2", 1200, 100, List.of("greedy")), "CODEFORCES", accountId, Instant.now());
        // 未映射的原始标签不算作一个分类
        solve(catalogProblem("CODEFORCES", "C3", 1200, 100, List.of("some-unknown-tag")),
                "CODEFORCES", accountId, Instant.now());

        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(find(result, "CATEGORY_3").progress()).isEqualTo(2);
        assertThat(find(result, "CATEGORY_3").unlocked()).isFalse();
    }

    @Test
    void platformBreadthCountsPlatformsWithSolves() {
        solve(catalogProblem("CODEFORCES", "X1", 1200, 100, List.of()), "CODEFORCES", accountId, Instant.now());
        Long atcoder = newAccount(Platform.ATCODER);
        solve(catalogProblem("ATCODER", "abc_a", 800, 100, List.of()), "ATCODER", atcoder, Instant.now());

        Map<String, Object> result = achievementService.achievements(userId);

        assertThat(find(result, "PLATFORM_2").unlocked()).isTrue();
        assertThat(find(result, "PLATFORM_3").unlocked()).isFalse();
    }

    @Test
    void achievementsRollBackWhenDataIsRemoved() {
        for (int i = 0; i < 10; i++) {
            solve(catalogProblem("CODEFORCES", "R" + i, 1200, 100, List.of()),
                    "CODEFORCES", accountId, Instant.now());
        }
        assertThat(find(achievementService.achievements(userId), "SOLVE_10").unlocked()).isTrue();

        // 解绑账号（级联删除通过记录）后成就随之回退
        jdbcTemplate.update("delete from platform_account where id = ?", accountId);

        assertThat(find(achievementService.achievements(userId), "SOLVE_10").unlocked()).isFalse();
    }

    @Test
    void contestAchievementStatesItIsNotOfficialParticipation() {
        Map<String, Object> result = achievementService.achievements(userId);
        assertThat(find(result, "CONTEST_5").description()).contains("不代表正式参赛计分");
    }

    @Test
    void dailyProblemIsStableWithinTheSameDay() {
        for (int i = 0; i < 30; i++) {
            catalogProblem("CODEFORCES", "DP" + i, 1000 + i * 20, 10000 - i * 100, List.of("dp"));
        }

        Map<String, Object> first = achievementService.dailyProblem(userId, "CODEFORCES");
        Map<String, Object> second = achievementService.dailyProblem(userId, "CODEFORCES");

        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = (Map<String, Object>) first.get("problem");
        @SuppressWarnings("unchecked")
        Map<String, Object> p2 = (Map<String, Object>) second.get("problem");
        assertThat(p1).isNotNull();
        assertThat(p1.get("problemKey")).isEqualTo(p2.get("problemKey"));
        assertThat(first.get("date")).isEqualTo(TimeSupport.today().toString());
        assertThat(String.valueOf(first.get("note"))).contains("不是平台官方的每日一题");
    }

    @Test
    void dailyProblemCarriesLinkTagsAndCampusProgress() {
        Long problemId = null;
        for (int i = 0; i < 20; i++) {
            Long id = catalogProblem("CODEFORCES", "DL" + i, 1200, 5000, List.of("dp", "greedy"));
            if (problemId == null) {
                problemId = id;
            }
        }

        Map<String, Object> result = achievementService.dailyProblem(userId, "CODEFORCES");
        @SuppressWarnings("unchecked")
        Map<String, Object> problem = (Map<String, Object>) result.get("problem");

        assertThat(String.valueOf(problem.get("url"))).startsWith("https://");
        assertThat(problem.get("difficultyLabel")).isEqualTo("CF 1200");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) problem.get("tags");
        assertThat(tags).extracting(t -> t.get("category")).contains("动态规划", "贪心");
        assertThat(result.get("solvedByMe")).isEqualTo(false);
        assertThat(result.get("campusSolvedCount")).isEqualTo(0);

        // 通过之后标记翻转，并计入校内通过人数
        Long chosenId = ((Number) problem.get("id")).longValue();
        solve(chosenId, "CODEFORCES", accountId, Instant.now());
        Map<String, Object> after = achievementService.dailyProblem(userId, "CODEFORCES");
        assertThat(after.get("solvedByMe")).isEqualTo(true);
        assertThat(after.get("campusSolvedCount")).isEqualTo(1);
        jdbcTemplate.update("update platform_account set ownership_status='RESOLVED' where id=?", accountId);
        Map<String, Object> unverified = achievementService.dailyProblem(userId, "CODEFORCES");
        assertThat(unverified.get("campusSolvedCount")).isEqualTo(0);
        assertThat(unverified.get("solvedByMe")).isEqualTo(true);
    }

    @Test
    void dailyProblemWorksWhenPlatformHasNoSolvedCount() {
        // AtCoder Problems 不提供通过人数，缺这个字段不能把整个平台排除掉
        for (int i = 0; i < 15; i++) {
            catalogProblem("ATCODER", "abc_" + i, 1100 + i * 20, null, List.of());
        }

        Map<String, Object> result = achievementService.dailyProblem(userId, "ATCODER");

        @SuppressWarnings("unchecked")
        Map<String, Object> problem = (Map<String, Object>) result.get("problem");
        assertThat(problem).isNotNull();
        assertThat(problem.get("platform")).isEqualTo("ATCODER");
        assertThat(problem.get("solvedCount")).isNull();
    }

    @Test
    void dailyProblemReportsMissingCatalogInsteadOfGuessing() {
        Map<String, Object> result = achievementService.dailyProblem(userId, "CODEFORCES");

        assertThat(result.get("problem")).isNull();
        assertThat(String.valueOf(result.get("note"))).contains("题库尚未同步");
    }

    @Test
    void dailyProblemDiffersAcrossPlatformsButStaysInBand() {
        for (int i = 0; i < 25; i++) {
            catalogProblem("CODEFORCES", "CF" + i, 1100 + i * 10, 9000 - i, List.of());
            catalogProblem("ATCODER", "AT" + i, 1100 + i * 10, 9000 - i, List.of());
        }

        Map<String, Object> cf = achievementService.dailyProblem(userId, "CODEFORCES");
        Map<String, Object> at = achievementService.dailyProblem(userId, "ATCODER");

        @SuppressWarnings("unchecked")
        Map<String, Object> cfProblem = (Map<String, Object>) cf.get("problem");
        @SuppressWarnings("unchecked")
        Map<String, Object> atProblem = (Map<String, Object>) at.get("problem");
        assertThat(cfProblem.get("platform")).isEqualTo("CODEFORCES");
        assertThat(atProblem.get("platform")).isEqualTo("ATCODER");
        int difficulty = ((Number) cfProblem.get("difficultyValue")).intValue();
        assertThat(difficulty).isBetween(1000, 1600);
    }
}
