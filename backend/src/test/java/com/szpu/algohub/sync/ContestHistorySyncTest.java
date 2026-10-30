package com.szpu.algohub.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.szpu.algohub.account.AccountService;
import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformRegistry;

/**
 * 比赛与 Rating：跟着提交历史一起同步、在同一个核对执行编号的事务里保存；重复同步不重复；
 * 取失败时历史照常保存、账号记为部分成功；只给本人看。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContestHistorySyncTest {

    @Autowired
    private SyncService syncService;
    @Autowired
    private SyncTaskMapper taskMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PlatformRegistry registry;

    private final PlatformAdapter codeforces = mock(PlatformAdapter.class);
    private Long userId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from sync_task");
        jdbc.execute("delete from platform_throttle");
        userId = newUser();
        otherId = newUser();
        when(registry.require(Platform.CODEFORCES)).thenReturn(codeforces);
        when(registry.find(any())).thenAnswer(inv -> java.util.Optional.of(registry.require(inv.getArgument(0))));
        when(codeforces.capability()).thenReturn(new PlatformCapability(Platform.CODEFORCES, true, true, false, false,
                "OFFICIAL", "OFFICIAL", true, "test"));
        when(codeforces.contestHistorySupported()).thenReturn(true);
        doReturn(new SubmissionPage(List.of(accepted("1")), "1", false, true)).when(codeforces).fetchSubmissions(any());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id in (?, ?)", userId, otherId);
        jdbc.execute("delete from problem where problem_key like 'RATE-%'");
        jdbc.execute("delete from sync_task");
    }

    private Long newUser() {
        Long id = jdbc.queryForObject("insert into app_user (username, password_hash, display_name) values (?, 'x', 'rating') returning id",
                Long.class, "rating-" + UUID.randomUUID());
        jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", id, id + "@rating.invalid");
        return id;
    }

    private Long account(Long owner) {
        return jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, supports_history)
                values (?, 'CODEFORCES', ?, 'rating', 'RESOLVED', true) returning id
                """, Long.class, owner, "rate-" + UUID.randomUUID());
    }

    private void run(Long accountId) {
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        jdbc.update("update sync_task set next_run_at = now() where account_id = ? and status = 'PENDING'", accountId);
        jdbc.execute("delete from platform_throttle");
        SyncTask task = taskMapper.claimNext("worker-rating", UUID.randomUUID().toString(), 120, 1);
        syncService.execute(task);
    }

    private static FetchedSubmission accepted(String key) {
        FetchedProblem problem = new FetchedProblem("RATE-" + key, "题 " + key, null, null, null, null, List.of(), false);
        return new FetchedSubmission("rate-sub-" + key, problem, "OK", true, Instant.parse("2026-09-01T10:00:00Z"),
                "EXACT", null, null);
    }

    private static RatedContest contest(String key, int oldRating, int newRating, boolean rated, String at) {
        return new RatedContest(key, "Round " + key, 100, oldRating, newRating, null, rated, Instant.parse(at));
    }

    private PlatformAccount mine(Long accountId) {
        return accountService.listMine(userId).stream().filter(a -> a.getId().equals(accountId)).findFirst().orElseThrow();
    }

    private static Map<String, Object> item(PlatformAccount account, String item) {
        return account.getDataItems().stream().filter(row -> item.equals(row.get("item"))).findFirst().orElse(null);
    }

    private int rows(Long accountId) {
        return jdbc.queryForObject("select count(*) from contest_participation where account_id = ?", Integer.class, accountId);
    }

    @Test
    void contestsAreSavedWithHistoryAndResyncUpdatesWithoutDuplicates() {
        Long accountId = account(userId);
        doReturn(List.of(contest("100", 0, 500, true, "2026-01-01T12:00:00Z"),
                contest("101", 500, 500, false, "2026-02-01T12:00:00Z"),
                contest("102", 500, 820, true, "2026-03-01T12:00:00Z"))).when(codeforces).fetchContestHistory(any());

        run(accountId);

        PlatformAccount account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("OK");
        assertThat(rows(accountId)).isEqualTo(3);
        assertThat(account.getContests()).isEqualTo(3);
        assertThat(account.getRatedContests()).isEqualTo(2);
        assertThat(account.getCurrentRating()).isEqualTo(820);
        assertThat(account.getMaxRating()).isEqualTo(820);
        assertThat(item(account, "RATING")).containsEntry("status", "SUCCESS").containsEntry("observedCount", 3)
                .containsEntry("detail", "参加 3 场（2 场计分），当前 820，最高 820");

        // 第二轮：一场被重算、又多了一场；只更新和追加，不重复
        doReturn(List.of(contest("100", 0, 500, true, "2026-01-01T12:00:00Z"),
                contest("101", 500, 500, false, "2026-02-01T12:00:00Z"),
                contest("102", 500, 810, true, "2026-03-01T12:00:00Z"),
                contest("103", 810, 760, true, "2026-04-01T12:00:00Z"))).when(codeforces).fetchContestHistory(any());
        run(accountId);

        account = mine(accountId);
        assertThat(rows(accountId)).isEqualTo(4);
        assertThat(account.getCurrentRating()).isEqualTo(760);
        assertThat(account.getMaxRating()).isEqualTo(810);
        assertThat(jdbc.queryForObject("select new_rating from contest_participation where account_id = ? and contest_key = '102'",
                Integer.class, accountId)).isEqualTo(810);
    }

    @Test
    void ratingFailureKeepsHistoryAndOldContestsAndIsPartial() {
        Long accountId = account(userId);
        doReturn(List.of(contest("100", 0, 500, true, "2026-01-01T12:00:00Z"))).when(codeforces).fetchContestHistory(any());
        run(accountId);

        doThrow(new ApiException(ErrorKind.UPSTREAM_ERROR, "Codeforces 源站返回 502")).when(codeforces).fetchContestHistory(any());
        doReturn(new SubmissionPage(List.of(accepted("1"), accepted("2")), "2", false, true)).when(codeforces).fetchSubmissions(any());
        run(accountId);

        PlatformAccount account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("PARTIAL");
        assertThat(item(account, "HISTORY")).containsEntry("status", "SUCCESS");
        assertThat(item(account, "RATING")).containsEntry("status", "FAILED").containsEntry("errorKind", "UPSTREAM_ERROR")
                .containsEntry("observedCount", 1);
        assertThat(item(account, "RATING").get("lastSuccessAt")).isNotNull();
        assertThat(account.getCollectedProblems()).isEqualTo(2);
        // 上次取到的比赛原样保留
        assertThat(rows(accountId)).isEqualTo(1);
        assertThat(account.getCurrentRating()).isEqualTo(500);
    }

    @Test
    void contestsAreFetchedOnlyAfterTheLastHistoryPage() {
        Long accountId = account(userId);
        doReturn(new SubmissionPage(List.of(accepted("1")), "1", true, false)).when(codeforces).fetchSubmissions(any());

        run(accountId);

        verify(codeforces, never()).fetchContestHistory(any());
        assertThat(item(mine(accountId), "RATING")).isNull();
    }

    @Test
    void ratingHistoryEndpointShowsOnlyMyOwnAccounts() throws Exception {
        Long mineId = account(userId);
        Long theirs = account(otherId);
        doReturn(List.of(contest("200", 0, 1200, true, "2026-05-01T12:00:00Z"))).when(codeforces).fetchContestHistory(any());
        run(mineId);
        run(theirs);

        String token = authService.issueSession(userMapper.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS")
                .view().accessToken();
        mockMvc.perform(get("/api/stats/rating-history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].accountId").value(mineId))
                .andExpect(jsonPath("$.data[0].contests[0].contestKey").value("200"))
                .andExpect(jsonPath("$.data[0].contests[0].newRating").value(1200));
        mockMvc.perform(get("/api/stats/rating-history")).andExpect(status().isUnauthorized());
    }
}
