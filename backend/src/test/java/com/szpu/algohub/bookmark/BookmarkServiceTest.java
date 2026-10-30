package com.szpu.algohub.bookmark;

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
import com.szpu.algohub.bookmark.BookmarkService.SaveRequest;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.statistics.SolvedMapper;

/**
 * 收藏与复习记录的约束验证：
 * - 同一题重复收藏是幂等的，不会多出记录；
 * - 收藏、改状态、取消收藏都不改动真实的通过记录；
 * - 自报状态与平台记录的「是否已通过」分开展示，不互相推断；
 * - 别人的收藏与笔记读不到；
 * - 复习日期按北京时间自然日判断是否到期。
 */
@SpringBootTest
class BookmarkServiceTest {

    @Autowired
    private BookmarkService bookmarkService;
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

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate daily_stat, user_solved_problem, submission, sync_cursor, sync_task, "
                + "total_snapshot, platform_credential, problem_group_member, problem_group, "
                + "platform_account, problem_tag, problem restart identity cascade");
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'mark-test%'");

        userA = newUser("mark-test-a-" + System.nanoTime());
        userB = newUser("mark-test-b-" + System.nanoTime());
        accountA = newAccount(userA);
    }

    private Long newUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName("收藏测试");
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
        account.setHandle("mark-handle");
        account.setOwnershipStatus("RESOLVED");
        account.setAuthMode("PUBLIC");
        account.setSyncStatus("OK");
        account.setSupportsHistory(true);
        accountMapper.insert(account);
        return account.getId();
    }

    private Long problem(String key, List<String> tags) {
        jdbcTemplate.update("""
                insert into problem (platform, problem_key, title, url, difficulty_label, difficulty_source,
                                     difficulty_value, tags_complete, catalog_synced_at, updated_at)
                values ('CODEFORCES', ?, ?, ?, 'CF 1300', 'OFFICIAL', 1300, true, now(), now())
                """, key, "题目 " + key, "https://codeforces.com/problemset/problem/" + key);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private long solvedRows(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from user_solved_problem where user_id=?", Long.class, userId);
        return count == null ? 0 : count;
    }

    @Test
    void bookmarkingTheSameProblemTwiceUpdatesInsteadOfDuplicating() {
        Long id = problem("200A", List.of("dp"));

        Map<String, Object> first = bookmarkService.save(userA, new SaveRequest(id, "LATER", "先放着", null));
        Map<String, Object> second = bookmarkService.save(userA, new SaveRequest(id, "REVIEW", "回头再看", null));

        assertThat(first.get("created")).isEqualTo(true);
        assertThat(second.get("created")).isEqualTo(false);

        Map<String, Object> list = bookmarkService.list(userA, null, null, null, false, 1, 20);
        assertThat(list.get("total")).isEqualTo(1L);
        assertThat(items(list).get(0).get("status")).isEqualTo("REVIEW");
        assertThat(items(list).get(0).get("note")).isEqualTo("回头再看");
    }

    @Test
    void bookmarkingNeverTouchesRealAcceptedData() {
        Long solved = problem("201A", List.of("greedy"));
        Long unsolved = problem("201B", List.of("greedy"));
        solvedMapper.upsert(userA, accountA, solved, Platform.CODEFORCES.name(), Instant.now(), "EXACT", "SYNC");
        long before = solvedRows(userA);

        // 把没通过的题标成“自己认为过关了”，通过记录也不该多一条
        bookmarkService.save(userA, new SaveRequest(unsolved, "DONE", null, null));
        // 把通过的题取消收藏，通过记录也不该少一条
        bookmarkService.save(userA, new SaveRequest(solved, "REVIEW", null, null));
        bookmarkService.remove(userA, solved);

        assertThat(solvedRows(userA)).isEqualTo(before);

        Map<String, Object> list = bookmarkService.list(userA, null, null, null, false, 1, 20);
        Map<String, Object> row = items(list).get(0);
        // 自报状态是 DONE，但平台记录里并没有通过——两者分开展示，不互相推断
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("accepted")).isEqualTo(false);
    }

    @Test
    void acceptedFlagComesFromSyncedDataNotFromTheBookmark() {
        Long id = problem("202A", List.of("math"));
        solvedMapper.upsert(userA, accountA, id, Platform.CODEFORCES.name(), Instant.now(), "EXACT", "SYNC");
        bookmarkService.save(userA, new SaveRequest(id, "LATER", null, null));

        Map<String, Object> row = items(bookmarkService.list(userA, null, null, null, false, 1, 20)).get(0);

        assertThat(row.get("accepted")).isEqualTo(true);
        assertThat(row.get("acAt")).isNotNull();
        assertThat(String.valueOf(row.get("url"))).startsWith("https://");
    }

    @Test
    void otherUsersBookmarksAndNotesAreNotReadable() {
        Long id = problem("203A", List.of("dp"));
        bookmarkService.save(userA, new SaveRequest(id, "REVIEW", "这是甲的私人笔记", null));

        Map<String, Object> asB = bookmarkService.list(userB, null, null, null, false, 1, 20);

        assertThat(asB.get("total")).isEqualTo(0L);
        assertThat(items(asB)).isEmpty();
        // 乙删不掉甲的收藏
        assertThat(bookmarkService.remove(userB, id).get("removed")).isEqualTo(false);
        assertThat(bookmarkService.list(userA, null, null, null, false, 1, 20).get("total")).isEqualTo(1L);
    }

    @Test
    void dueOnlyUsesCampusDayAndCountsTodayAsDue() {
        LocalDate today = TimeSupport.today();
        Long dueToday = problem("204A", List.of("dp"));
        Long dueLater = problem("204B", List.of("dp"));
        bookmarkService.save(userA, new SaveRequest(dueToday, "REVIEW", null, today));
        bookmarkService.save(userA, new SaveRequest(dueLater, "REVIEW", null, today.plusDays(3)));

        Map<String, Object> due = bookmarkService.list(userA, null, null, null, true, 1, 20);

        assertThat(due.get("total")).isEqualTo(1L);
        assertThat(items(due).get(0).get("problemKey")).isEqualTo("204A");
        assertThat(items(due).get(0).get("due")).isEqualTo(true);
    }

    @Test
    void listCanBeFilteredByAlgorithmCategory() {
        bookmarkService.save(userA, new SaveRequest(problem("205A", List.of("dp")), "LATER", null, null));
        bookmarkService.save(userA, new SaveRequest(problem("205B", List.of("greedy")), "LATER", null, null));

        Map<String, Object> dp = bookmarkService.list(userA, null, null, "动态规划", false, 1, 20);

        assertThat(dp.get("total")).isEqualTo(1L);
        assertThat(items(dp).get(0).get("problemKey")).isEqualTo("205A");
        assertThat(items(dp).get(0).get("categories")).isEqualTo(List.of("动态规划"));
    }

    @Test
    void countsAreReportedPerStatus() {
        bookmarkService.save(userA, new SaveRequest(problem("206A", List.of()), "LATER", null, null));
        bookmarkService.save(userA, new SaveRequest(problem("206B", List.of()), "REVIEW", null,
                TimeSupport.today()));
        bookmarkService.save(userA, new SaveRequest(problem("206C", List.of()), "REVIEW", null, null));

        @SuppressWarnings("unchecked")
        Map<String, Object> counts =
                (Map<String, Object>) bookmarkService.list(userA, null, null, null, false, 1, 20).get("counts");

        assertThat(((Map<?, ?>) counts.get("LATER")).get("count")).isEqualTo(1L);
        assertThat(((Map<?, ?>) counts.get("REVIEW")).get("count")).isEqualTo(2L);
        assertThat(((Map<?, ?>) counts.get("REVIEW")).get("due")).isEqualTo(1L);
        assertThat(((Map<?, ?>) counts.get("DONE")).get("count")).isEqualTo(0L);
        assertThat(((Map<?, ?>) counts.get("TOTAL")).get("count")).isEqualTo(3L);
    }

    @Test
    void unknownStatusIsRejected() {
        Long id = problem("207A", List.of());

        assertThatThrownBy(() -> bookmarkService.save(userA, new SaveRequest(id, "MASTERED", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("状态只支持");
    }

    @Test
    void bookmarkingAProblemThatIsNotInTheDatabaseIsRejected() {
        assertThatThrownBy(() -> bookmarkService.save(userA, new SaveRequest(999999L, "LATER", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("题目不存在");
    }

    @Test
    void statusOfReturnsMarksForAListOfProblemsInOneCall() {
        Long a = problem("208A", List.of());
        Long b = problem("208B", List.of());
        Long c = problem("208C", List.of());
        bookmarkService.save(userA, new SaveRequest(a, "LATER", null, null));
        bookmarkService.save(userA, new SaveRequest(b, "REVIEW", null, null));

        Map<Long, String> marks = bookmarkService.statusOf(userA, List.of(a, b, c));

        assertThat(marks).containsEntry(a, "LATER").containsEntry(b, "REVIEW");
        assertThat(marks).doesNotContainKey(c);
        assertThat(bookmarkService.statusOf(userB, List.of(a, b, c))).isEmpty();
    }
}
