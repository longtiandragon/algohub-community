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
import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.problem.ProblemListService.CreateListRequest;
import com.szpu.algohub.problem.ProblemListService.GenerateRequest;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 题单的口径验证：
 * - 完成进度按已通过记录实时算，数据回退进度也回退；
 * - 自动生成的题单记录并展示生成规则，不冒充人工精选；
 * - 完成榜与排行榜口径一致（尊重隐私设置、只算平台可核对的账号）。
 */
@SpringBootTest
class ProblemListServiceTest {

    @Autowired
    private ProblemListService listService;
    @Autowired
    private ProblemListMapper listMapper;
    @Autowired
    private AuthService authService;
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
        jdbcTemplate.execute("truncate problem_list_item, problem_list, daily_stat, user_solved_problem, "
                + "submission, sync_cursor, sync_task, total_snapshot, platform_credential, "
                + "problem_group_member, problem_group, platform_account, problem_tag, problem "
                + "restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'list-test%'");

        userA = newUser("list-test-a-" + System.nanoTime(), "题单甲");
        userB = newUser("list-test-b-" + System.nanoTime(), "题单乙");
        accountA = newAccount(userA);
        accountB = newAccount(userB);
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
        // 完成榜只列本校同学：测试同学都加入同一所学校
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) "
                + "select id, ?, 'APPROVED' from school order by id limit 1", user.getId());
        return user.getId();
    }

    private Long newAccount(Long userId) {
        PlatformAccount account = new PlatformAccount();
        account.setUserId(userId);
        account.setPlatform(Platform.CODEFORCES.name());
        account.setPlatformUid("uid-" + System.nanoTime());
        account.setHandle("handle");
        account.setOwnershipStatus("VERIFIED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long catalogProblem(String key, Integer rating, Integer solvedCount, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values ('CODEFORCES', ?, ?, ?, ?, ?, 'OFFICIAL', ?, ?, true, now(), now())
                """, key, "题目 " + key, "https://codeforces.com/problemset/problem/" + key,
                rating == null ? null : String.valueOf(rating),
                rating == null ? null : "CF " + rating, rating, solvedCount);
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

    private void solve(Long userId, Long accountId, Long problemId) {
        solvedMapper.upsert(userId, accountId, problemId, Platform.CODEFORCES.name(),
                Instant.now(), "EXACT", "SYNC");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.get("items");
    }

    @Test
    void createdListStartsEmptyAndVisible() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("cf-starter", "CF 入门", "适合刚开始刷 CF 的同学", "入门", "CODEFORCES", 10, null));

        assertThat(list.getCode()).isEqualTo("CF-STARTER");
        assertThat(list.getSource()).isEqualTo("MANUAL");
        assertThat(list.isVisible()).isTrue();
        assertThat(listService.listAll(userA, false, "ALL")).hasSize(1);
        assertThat(listService.detail(list.getId(), userA).get("itemCount")).isEqualTo(0);
    }

    @Test
    void duplicateCodeAndBadCodeAreRejected() {
        listService.create(userA, true, new CreateListRequest("CF-A", "题单", null, null, null, null, null));

        assertThatThrownBy(() -> listService.create(userA, true,
                new CreateListRequest("cf-a", "重复", null, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("已存在相同标识");
        assertThatThrownBy(() -> listService.create(userA, true,
                new CreateListRequest("有中文", "非法", null, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("只能包含字母");
    }

    @Test
    void progressCountsOnlySolvedProblemsAndRollsBack() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-P", "进度题单", null, null, "CODEFORCES", null, null));
        Long p1 = catalogProblem("1A", 800, 1000, List.of("math"));
        Long p2 = catalogProblem("2A", 900, 900, List.of("dp"));
        Long p3 = catalogProblem("3A", 1000, 800, List.of());
        listService.addItems(list.getId(), List.of(p1, p2, p3), null);

        solve(userA, accountA, p1);
        solve(userA, accountA, p2);

        Map<String, Object> detail = listService.detail(list.getId(), userA);
        assertThat(detail.get("itemCount")).isEqualTo(3);
        assertThat(detail.get("solvedCount")).isEqualTo(2L);
        assertThat(items(detail)).filteredOn(i -> Boolean.TRUE.equals(i.get("solvedByMe"))).hasSize(2);

        // 列表视图里的进度同样是实时算的
        ProblemList overview = listService.listAll(userA, false, "ALL").get(0);
        assertThat(overview.getItemCount()).isEqualTo(3);
        assertThat(overview.getSolvedCount()).isEqualTo(2);

        // 解绑账号后通过记录级联删除，进度回退
        jdbcTemplate.update("delete from platform_account where id = ?", accountA);
        assertThat(listService.detail(list.getId(), userA).get("solvedCount")).isEqualTo(0L);
    }

    @Test
    void itemsCarryLinkDifficultyAndTags() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-T", "标签题单", null, null, "CODEFORCES", null, null));
        Long p1 = catalogProblem("4A", 1200, 500, List.of("dp", "greedy"));
        listService.addItems(list.getId(), List.of(p1), "先做这道");

        Map<String, Object> item = items(listService.detail(list.getId(), userA)).get(0);

        assertThat(String.valueOf(item.get("url"))).startsWith("https://codeforces.com/");
        assertThat(item.get("difficultyLabel")).isEqualTo("CF 1200");
        assertThat(item.get("note")).isEqualTo("先做这道");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) item.get("tags");
        assertThat(tags).extracting(t -> t.get("category")).contains("动态规划", "贪心");
    }

    @Test
    void addingSameProblemTwiceIsIgnored() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-D", "去重题单", null, null, null, null, null));
        Long p1 = catalogProblem("5A", 800, 100, List.of());
        listService.addItems(list.getId(), List.of(p1), null);

        Map<String, Object> result = listService.addItems(list.getId(), List.of(p1), null);

        assertThat(result.get("added")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Long> skipped = (List<Long>) result.get("skipped");
        assertThat(skipped).containsExactly(p1);
        assertThat(listService.detail(list.getId(), userA).get("itemCount")).isEqualTo(1);
    }

    @Test
    void oversizedBatchesAreRejected() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-BIG", "大批量", null, null, null, null, null));
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, ProblemListService.MAX_ITEMS_PER_REQUEST + 1)
                .boxed().toList();
        assertThatThrownBy(() -> listService.addItems(list.getId(), tooMany, null)).hasMessageContaining("一次最多处理 500");
        assertThatThrownBy(() -> listService.reorder(list.getId(), tooMany)).hasMessageContaining("一次最多处理 500");
    }

    @Test
    void reorderChangesDisplayOrderOnly() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-O", "排序题单", null, null, null, null, null));
        Long p1 = catalogProblem("6A", 800, 100, List.of());
        Long p2 = catalogProblem("7A", 900, 100, List.of());
        listService.addItems(list.getId(), List.of(p1, p2), null);

        listService.reorder(list.getId(), List.of(p2, p1));

        assertThat(items(listService.detail(list.getId(), userA)))
                .extracting(i -> i.get("problemKey"))
                .containsExactly("7A", "6A");
    }

    @Test
    void generatedListRecordsItsRuleAndSaysItIsNotCurated() {
        for (int i = 0; i < 40; i++) {
            catalogProblem("G" + i, 800 + (i % 5) * 100, 10000 - i * 10, List.of("implementation"));
        }

        Map<String, Object> result = listService.generate(userA, true,
                new GenerateRequest("CF-GEN", null, null, "CODEFORCES", 800, 1000, 10, null, null, null, null));

        ProblemList list = (ProblemList) result.get("list");
        assertThat(list.getSource()).isEqualTo("GENERATED");
        assertThat(list.getGeneratedRule()).contains("难度 800–1000").contains("按平台公开通过人数");
        assertThat(result.get("added")).isEqualTo(10);
        assertThat(String.valueOf(listService.detail(list.getId(), userA).get("note")))
                .contains("不是人工精选");
        // 题目都落在指定区间内
        assertThat(items(listService.detail(list.getId(), userA)))
                .allMatch(i -> ((Number) i.get("difficultyValue")).intValue() >= 800
                        && ((Number) i.get("difficultyValue")).intValue() <= 1000);
    }

    @Test
    void generateRequiresSyncedCatalogAndSaneRange() {
        assertThatThrownBy(() -> listService.generate(userA, true,
                new GenerateRequest("CF-X", null, null, "CODEFORCES", 800, 1000, 10, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("题库尚未同步");

        catalogProblem("H1", 900, 100, List.of());
        assertThatThrownBy(() -> listService.generate(userA, true,
                new GenerateRequest("CF-Y", null, null, "CODEFORCES", 1500, 900, 10, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("下限不能大于上限");
        assertThatThrownBy(() -> listService.generate(userA, true,
                new GenerateRequest("CF-Z", null, null, "CODEFORCES", 2000, 2200, 10, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("没有题目");
    }

    @Test
    void leaderboardRespectsPrivacyAndCountsDistinctProblems() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-L", "榜单题单", null, null, null, null, null));
        Long p1 = catalogProblem("8A", 800, 100, List.of());
        Long p2 = catalogProblem("9A", 900, 100, List.of());
        listService.addItems(list.getId(), List.of(p1, p2), null);

        solve(userA, accountA, p1);
        solve(userA, accountA, p2);
        solve(userB, accountB, p1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) listService.leaderboard(list.getId(), userA, 10).get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("displayName")).isEqualTo("题单甲");
        assertThat(((Number) rows.get(0).get("solvedCount")).intValue()).isEqualTo(2);

        // 关闭排行榜展示后从完成榜消失
        authService.updatePrivacy(userB, new PrivacyRequest(null, false, null, null, null),
                "127.0.0.1", "JUnit");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after =
                (List<Map<String, Object>>) listService.leaderboard(list.getId(), userA, 10).get("rows");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("displayName")).isEqualTo("题单甲");
        jdbcTemplate.update("update platform_account set ownership_status='RESOLVED' where id=?", accountA);
        assertThat((List<?>) listService.leaderboard(list.getId(), userA, 10).get("rows")).isEmpty();
    }

    @Test
    void hiddenListsAreInvisibleToStudentsButVisibleToAdminView() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-H", "隐藏题单", null, null, null, null, null));
        listService.update(list.getId(),
                new CreateListRequest(null, "隐藏题单", null, null, null, null, null), false);

        assertThat(listService.listAll(userA, false, "ALL")).isEmpty();
        assertThat(listService.listAll(userA, true, "ALL")).hasSize(1);
    }

    @Test
    void deletingListRemovesItemsButKeepsProblemsAndSolves() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-DEL", "待删题单", null, null, null, null, null));
        Long p1 = catalogProblem("10A", 800, 100, List.of());
        listService.addItems(list.getId(), List.of(p1), null);
        solve(userA, accountA, p1);

        listService.delete(list.getId());

        assertThat(listMapper.findById(list.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from problem where id = ?", Integer.class, p1))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem where problem_id = ?", Integer.class, p1))
                .isEqualTo(1);
    }

    @Test
    void removingItemRequiresItToBeInTheList() {
        ProblemList list = listService.create(userA, true,
                new CreateListRequest("CF-R", "移除题单", null, null, null, null, null));
        Long p1 = catalogProblem("11A", 800, 100, List.of());

        assertThatThrownBy(() -> listService.removeItem(list.getId(), p1))
                .isInstanceOf(ApiException.class).hasMessageContaining("不在题单中");
    }
}
