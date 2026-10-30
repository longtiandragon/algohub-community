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
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.problem.ProblemGroupDtos.CreateGroupRequest;
import com.szpu.algohub.problem.ProblemGroupDtos.GroupView;
import com.szpu.algohub.statistics.SolvedMapper;
import com.szpu.algohub.statistics.StatisticsMapper;
import com.szpu.algohub.statistics.StatisticsService;

/**
 * 跨平台题目映射的口径验证：
 * - 只有人工映射到同一题目组的题目才合并计数；
 * - 一道题最多属于一个组，冲突时明确报错而不是静默改口径；
 * - 映射不改变各平台原始通过数，只影响“去重后的唯一题量”；
 * - 没有任何映射时，去重结果等于各平台合计。
 */
@SpringBootTest
class ProblemGroupServiceTest {

    @Autowired
    private ProblemGroupService groupService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private PlatformAccountMapper accountMapper;
    @Autowired
    private SolvedMapper solvedMapper;
    @Autowired
    private StatisticsMapper statisticsMapper;
    @Autowired
    private com.szpu.algohub.statistics.CampusRankingService campusRankingService;
    @Autowired
    private StatisticsService statisticsService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long cfAccountId;
    private Long luoguAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'group-test%'");

        AppUser user = new AppUser();
        user.setUsername("group-test-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("映射测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        cfAccountId = newAccount(Platform.CODEFORCES, "cf-handle");
        luoguAccountId = newAccount(Platform.LUOGU, "luogu-handle");
    }

    private Long newAccount(Platform platform, String handle) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(platform.name());
        account.setPlatformUid(platform.name() + "-" + System.nanoTime());
        account.setHandle(handle);
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("IDLE");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long solve(Platform platform, Long accountId, String key, String title) {
        Long problemId = problemService.upsert(platform,
                new FetchedProblem(key, title, "https://example.invalid/" + key,
                        null, null, null, List.of(), false));
        solvedMapper.upsert(userId, accountId, problemId, platform.name(), Instant.now(),
                "EARLIEST_KNOWN", "SYNC");
        return problemId;
    }

    @Test
    void withoutMappingUniqueEqualsPlatformSum() {
        solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        solve(Platform.LUOGU, luoguAccountId, "P1001", "A+B Problem");

        Map<String, Object> summary = statisticsMapper.uniqueSolvedSummary(userId);
        assertThat(((Number) summary.get("totalSolved")).intValue()).isEqualTo(2);
        assertThat(((Number) summary.get("uniqueSolved")).intValue()).isEqualTo(2);
        assertThat(((Number) summary.get("mappedSolved")).intValue()).isZero();

        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) statisticsService.profile(userId)
                .get("crossPlatformMapping");
        assertThat(mapping.get("mergedDuplicates")).isEqualTo(0L);
        assertThat((String) mapping.get("note")).contains("尚未建立任何跨平台题目映射");
    }

    @Test
    void mappedProblemsAreCountedOnceInUniqueTotal() {
        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        Long luoguProblem = solve(Platform.LUOGU, luoguAccountId, "CF4A", "西瓜（搬运）");
        solve(Platform.LUOGU, luoguAccountId, "P1001", "A+B Problem");

        GroupView group = groupService.create(userId, new CreateGroupRequest(
                "CF/4A", "Watermelon", "洛谷搬运题", List.of(cfProblem, luoguProblem)));
        assertThat(group.members()).hasSize(2);

        Map<String, Object> summary = statisticsMapper.uniqueSolvedSummary(userId);
        assertThat(((Number) summary.get("totalSolved")).intValue()).isEqualTo(3);
        // 两道映射题合并为一道，第三道未映射仍单独计数
        assertThat(((Number) summary.get("uniqueSolved")).intValue()).isEqualTo(2);
        assertThat(((Number) summary.get("mappedSolved")).intValue()).isEqualTo(2);

        // 各平台原始通过数不受映射影响
        Map<String, Integer> byPlatform = statisticsMapper.platformSolved(userId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("platform"),
                        row -> ((Number) row.get("solved")).intValue()));
        assertThat(byPlatform).containsEntry("CODEFORCES", 1).containsEntry("LUOGU", 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) statisticsService.profile(userId)
                .get("crossPlatformMapping");
        assertThat(mapping.get("uniqueSolvedAfterMapping")).isEqualTo(2L);
        assertThat(mapping.get("mergedDuplicates")).isEqualTo(1L);
        assertThat((String) mapping.get("note")).contains("未建立映射的题目仍按各平台分别计数");
    }

    @Test
    void rankingShowsBothTotalAndUniqueCounts() {
        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        Long luoguProblem = solve(Platform.LUOGU, luoguAccountId, "CF4A", "西瓜（搬运）");
        groupService.create(userId, new CreateGroupRequest("CF/4A", "Watermelon", null,
                List.of(cfProblem, luoguProblem)));

        // 校内累计榜只收已验证归属、在本校的账号
        jdbcTemplate.update("update platform_account set ownership_status = 'VERIFIED' where user_id = ?", userId);
        // 洛谷完成浏览器评测回填后才参与榜单。
        jdbcTemplate.update("update platform_account set history_complete = true where id = ?", luoguAccountId);
        Long school = jdbcTemplate.queryForObject("select id from school order by id limit 1", Long.class);
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) values (?, ?, 'APPROVED')",
                school, userId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) campusRankingService
                .ranking(userId, "TOTAL", "FIRST_AC", null, null, null, 50).get("rows");
        Map<String, Object> row = rows.stream()
                .filter(r -> userId.equals(((Number) r.get("userId")).longValue()))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) row.get("solved")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("uniqueSolved")).intValue()).isEqualTo(1);
    }

    @Test
    void oneProblemBelongsToAtMostOneGroup() {
        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        groupService.create(userId, new CreateGroupRequest("CF/4A", "Watermelon", null, List.of(cfProblem)));
        GroupView other = groupService.create(userId, new CreateGroupRequest("OTHER/1", "另一个组", null, List.of()));

        assertThatThrownBy(() -> groupService.addMember(userId, other.id(), cfProblem, "SAME_PROBLEM"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已属于其他题目组");

        // 显式移除后可以重新归组
        groupService.removeMember(cfProblem);
        GroupView moved = groupService.addMember(userId, other.id(), cfProblem, "MIRROR");
        assertThat(moved.members()).hasSize(1);
        assertThat(moved.members().get(0).relation()).isEqualTo("MIRROR");
    }

    @Test
    void duplicateCanonicalKeyAndUnknownRelationAreRejected() {
        groupService.create(userId, new CreateGroupRequest("CF/4A", "Watermelon", null, List.of()));
        assertThatThrownBy(() -> groupService.create(userId,
                new CreateGroupRequest("cf/4a", "重复", null, List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已存在相同规范标识");

        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        GroupView group = groupService.create(userId, new CreateGroupRequest("CF/5A", null, null, List.of()));
        assertThatThrownBy(() -> groupService.addMember(userId, group.id(), cfProblem, "GUESSED_BY_TITLE"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未知的映射关系");
    }

    @Test
    void deletingGroupRestoresSeparateCounting() {
        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        Long luoguProblem = solve(Platform.LUOGU, luoguAccountId, "CF4A", "西瓜（搬运）");
        GroupView group = groupService.create(userId, new CreateGroupRequest("CF/4A", null, null,
                List.of(cfProblem, luoguProblem)));

        assertThat(((Number) statisticsMapper.uniqueSolvedSummary(userId).get("uniqueSolved")).intValue())
                .isEqualTo(1);

        groupService.deleteGroup(group.id());

        Map<String, Object> summary = statisticsMapper.uniqueSolvedSummary(userId);
        assertThat(((Number) summary.get("uniqueSolved")).intValue()).isEqualTo(2);
        assertThat(((Number) summary.get("mappedSolved")).intValue()).isZero();
        // 删除映射不会删除题目或通过记录
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_solved_problem where user_id = ?",
                Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void problemSearchShowsExistingMapping() {
        Long cfProblem = solve(Platform.CODEFORCES, cfAccountId, "4A", "Watermelon");
        groupService.create(userId, new CreateGroupRequest("CF/4A", "Watermelon", null, List.of(cfProblem)));

        var results = groupService.searchProblems("CODEFORCES", "4A", 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).groupKey()).isEqualTo("CF/4A");

        var byTitle = groupService.searchProblems(null, "watermelon", 10);
        assertThat(byTitle).extracting(ProblemGroupDtos.ProblemSearchItem::problemKey).contains("4A");
    }
}
