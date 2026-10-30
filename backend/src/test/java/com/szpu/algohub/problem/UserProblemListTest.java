package com.szpu.algohub.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.problem.ProblemListService.CreateListRequest;
import com.szpu.algohub.problem.ProblemListService.GenerateRequest;

/**
 * 学生自建题单、分享范围、收藏与按标签生成的验证：
 * - 自建题单默认仅自己可见，别人看不到也改不了；
 * - 改成校内共享后其他同学才能看到与收藏；
 * - 系统题单只有管理员能维护；
 * - 按标签分类生成时，所选题目必须真的属于该分类。
 */
@SpringBootTest
class UserProblemListTest {

    @Autowired
    private ProblemListService listService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;
    private Long userB;
    private Long adminId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate problem_list_favorite, problem_list_item, problem_list, "
                + "user_solved_problem, platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.update("delete from app_user where username like 'own-test%'");

        userA = newUser("own-test-a-" + System.nanoTime(), "甲同学", "STUDENT");
        userB = newUser("own-test-b-" + System.nanoTime(), "乙同学", "STUDENT");
        adminId = newUser("own-test-admin-" + System.nanoTime(), "管理员", "ADMIN");
    }

    private Long newUser(String username, String displayName, String role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        jdbcTemplate.update("insert into user_privacy (user_id) values (?) on conflict do nothing", user.getId());
        // 分享到本校的题单只对同校可见：测试同学都加入同一所学校
        jdbcTemplate.update("insert into school_member (school_id, user_id, status) "
                + "select id, ?, 'APPROVED' from school order by id limit 1", user.getId());
        return user.getId();
    }

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

    private CreateListRequest listRequest(String title, String shareScope) {
        return new CreateListRequest(null, title, null, null, null, null, shareScope);
    }

    @Test
    void studentListDefaultsToPrivateAndGetsGeneratedCode() {
        ProblemList list = listService.create(userA, false, listRequest("我的计划", null));

        assertThat(list.getOwnerId()).isEqualTo(userA);
        assertThat(list.getShareScope()).isEqualTo("PRIVATE");
        // 学生不用自己想标识，系统按用户生成且带前缀，不会与系统题单撞号
        assertThat(list.getCode()).startsWith("U" + userA + "-");
    }

    @Test
    void privateListIsInvisibleAndUneditableForOthers() {
        ProblemList list = listService.create(userA, false, listRequest("私密计划", "PRIVATE"));

        assertThat(listService.listAll(userA, false, "ALL")).extracting(ProblemList::getId).contains(list.getId());
        assertThat(listService.listAll(userB, false, "ALL")).extracting(ProblemList::getId)
                .doesNotContain(list.getId());

        assertThatThrownBy(() -> listService.requireReadable(list.getId(), userB, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("未公开");
        assertThatThrownBy(() -> listService.requireEditable(list.getId(), userB, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("只能修改自己创建的题单");
    }

    @Test
    void sharedListBecomesVisibleToOtherStudents() {
        ProblemList list = listService.create(userA, false, listRequest("共享计划", "SCHOOL"));

        assertThat(listService.listAll(userB, false, "ALL")).extracting(ProblemList::getId)
                .contains(list.getId());
        assertThat(listService.requireReadable(list.getId(), userB, false)).isNotNull();
        // 能看不等于能改
        assertThatThrownBy(() -> listService.requireEditable(list.getId(), userB, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void shareScopeCanBeChangedLater() {
        ProblemList list = listService.create(userA, false, listRequest("先私密", "PRIVATE"));
        assertThat(listService.listAll(userB, false, "ALL")).isEmpty();

        listService.update(list.getId(), new CreateListRequest(null, null, null, null, null, null, "SCHOOL"), null);

        assertThat(listService.listAll(userB, false, "ALL")).extracting(ProblemList::getId)
                .contains(list.getId());
        assertThatThrownBy(() -> listService.update(list.getId(),
                new CreateListRequest(null, null, null, null, null, null, "EVERYONE"), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("分享范围只能是");
    }

    @Test
    void systemListIsEditableOnlyByAdmin() {
        ProblemList system = listService.create(adminId, true,
                new CreateListRequest("SYS-1", "系统题单", null, null, null, null, null));

        assertThat(system.getOwnerId()).isNull();
        // 系统题单默认面向全站
        assertThat(system.getShareScope()).isEqualTo("PUBLIC");
        assertThat(listService.requireEditable(system.getId(), adminId, true)).isNotNull();
        assertThatThrownBy(() -> listService.requireEditable(system.getId(), userA, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("系统题单只能由管理员维护");
        // 学生仍然可以查看系统题单
        assertThat(listService.requireReadable(system.getId(), userA, false)).isNotNull();
    }

    @Test
    void favoriteMarksListAndFiltersByScope() {
        ProblemList shared = listService.create(userA, false, listRequest("共享计划", "SCHOOL"));

        listService.setFavorite(userB, shared.getId(), true, false);

        ProblemList seen = listService.listAll(userB, false, "ALL").stream()
                .filter(l -> l.getId().equals(shared.getId())).findFirst().orElseThrow();
        assertThat(seen.getFavorited()).isTrue();
        assertThat(seen.getFavoriteCount()).isEqualTo(1);
        assertThat(seen.getOwnerName()).isEqualTo("甲同学");

        assertThat(listService.listAll(userB, false, "FAVORITE")).hasSize(1);
        assertThat(listService.listAll(userB, false, "MINE")).isEmpty();
        assertThat(listService.listAll(userA, false, "MINE")).hasSize(1);

        listService.setFavorite(userB, shared.getId(), false, false);
        assertThat(listService.listAll(userB, false, "FAVORITE")).isEmpty();
    }

    @Test
    void detailCarriesOwnerAndFavoriteState() {
        // 详情接口要自带作者与收藏状态，前端不用为这几个字段再拉一次列表
        ProblemList list = listService.create(userA, false, listRequest("共享计划", "SCHOOL"));
        listService.setFavorite(userB, list.getId(), true, false);

        ProblemList seenByOwner = (ProblemList) listService.detail(list.getId(), userA).get("list");
        assertThat(seenByOwner.getOwnerName()).isEqualTo("甲同学");
        assertThat(seenByOwner.getFavoriteCount()).isEqualTo(1);
        assertThat(seenByOwner.getFavorited()).isFalse();

        ProblemList seenByFan = (ProblemList) listService.detail(list.getId(), userB).get("list");
        assertThat(seenByFan.getFavorited()).isTrue();
        assertThat(seenByFan.getShareScope()).isEqualTo("SCHOOL");
    }

    @Test
    void cannotFavoriteAListYouCannotSee() {
        ProblemList priv = listService.create(userA, false, listRequest("私密计划", "PRIVATE"));

        assertThatThrownBy(() -> listService.setFavorite(userB, priv.getId(), true, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("未公开");
    }

    @Test
    void generateByTagCategoryOnlyPicksMatchingProblems() {
        for (int i = 0; i < 8; i++) {
            catalogProblem("DP" + i, 1200, 1000 - i, List.of("dp"));
            catalogProblem("STR" + i, 1200, 900 - i, List.of("strings"));
        }

        Map<String, Object> result = listService.generate(userA, false,
                new GenerateRequest(null, "DP 专题", null, "CODEFORCES", 1100, 1300, 5,
                        null, null, "动态规划", "SCHOOL"));

        ProblemList list = (ProblemList) result.get("list");
        assertThat(list.getGeneratedRule()).contains("分类 动态规划");
        assertThat(list.getOwnerId()).isEqualTo(userA);
        assertThat(list.getShareScope()).isEqualTo("SCHOOL");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) listService.detail(list.getId(), userA).get("items");
        assertThat(items).hasSize(5);
        assertThat(items).allMatch(i -> String.valueOf(i.get("problemKey")).startsWith("DP"));
    }

    @Test
    void generateWithEmptyCategoryGivesActionableMessage() {
        catalogProblem("ONLY1", 1200, 100, List.of("strings"));

        assertThatThrownBy(() -> listService.generate(userA, false,
                new GenerateRequest(null, null, null, "CODEFORCES", 1100, 1300, 5,
                        null, null, "动态规划", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有「动态规划」分类的题目");
    }

    @Test
    void perUserListLimitIsEnforced() {
        jdbcTemplate.update("""
                insert into problem_list (code, title, source, visible, sort_order, created_by,
                                          owner_id, share_scope)
                select 'U' || ? || '-BULK' || g, '批量题单 ' || g, 'MANUAL', true, 100, ?, ?, 'PRIVATE'
                  from generate_series(1, 50) g
                """, userA, userA, userA);

        assertThatThrownBy(() -> listService.create(userA, false, listRequest("再来一个", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("最多创建");
    }

    @Test
    void deletingUserRemovesTheirListsAndFavorites() {
        ProblemList list = listService.create(userA, false, listRequest("待删计划", "SCHOOL"));
        listService.setFavorite(userB, list.getId(), true, false);

        userMapper.delete(userA);

        assertThat(jdbcTemplate.queryForObject("select count(*) from problem_list where id = ?",
                Integer.class, list.getId())).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from problem_list_favorite where list_id = ?",
                Integer.class, list.getId())).isEqualTo(0);
    }
}
