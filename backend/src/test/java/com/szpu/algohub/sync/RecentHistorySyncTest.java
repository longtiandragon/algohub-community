package com.szpu.algohub.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.szpu.algohub.account.AccountService;
import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformRegistry;

/**
 * 只公开最近通过记录的平台（力扣）：同一轮里取总量与最近明细；历史永远不算完整，
 * 最近一次通过的时间不当首次通过计入新增；总量的观察增量保留。
 */
@SpringBootTest
class RecentHistorySyncTest {

    private static final String NOTE = "只公开最近几道通过的题";

    @Autowired
    private SyncService syncService;
    @Autowired
    private SyncTaskMapper taskMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PlatformRegistry registry;

    private final PlatformAdapter leetcode = mock(PlatformAdapter.class);
    private Long userId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from sync_task");
        jdbc.execute("delete from platform_throttle");
        userId = jdbc.queryForObject("insert into app_user (username, password_hash, display_name) values (?, 'x', 'recent') returning id",
                Long.class, "recent-" + UUID.randomUUID());
        accountId = jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, supports_history)
                values (?, 'LEETCODE_CN', ?, 'recent', 'VERIFIED', false) returning id
                """, Long.class, userId, "recent-" + UUID.randomUUID());
        when(registry.require(Platform.LEETCODE_CN)).thenReturn(leetcode);
        when(registry.find(any())).thenAnswer(inv -> java.util.Optional.of(registry.require(inv.getArgument(0))));
        when(leetcode.capability()).thenReturn(new PlatformCapability(Platform.LEETCODE_CN, true, true, true, false,
                "OFFICIAL", "NONE", false, "test"));
        when(leetcode.partialHistoryNote()).thenReturn(NOTE);
        doReturn(new SubmissionPage(List.of(accepted("a", 1), accepted("b", 2)), "2", false, false))
                .when(leetcode).fetchSubmissions(any());
        doReturn(totals(42)).when(leetcode).fetchTotals(any());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
        jdbc.execute("delete from problem where problem_key like 'recent-%'");
        jdbc.execute("delete from sync_task");
    }

    private static FetchedSubmission accepted(String key, int id) {
        FetchedProblem problem = new FetchedProblem("recent-" + key, "题 " + key, null, null, null, "NONE", List.of(), false);
        return new FetchedSubmission(String.valueOf(id), problem, "AC", true, Instant.parse("2026-09-14T01:00:00Z"),
                "EXACT", null, null);
    }

    private static TotalsSnapshot totals(int solved) {
        return new TotalsSnapshot(solved, null, Map.of("EASY", 12, "MEDIUM", solved - 18, "HARD", 6), null);
    }

    private void run() {
        syncService.enqueueAccountSync(accountId, "LEETCODE_CN", 10);
        jdbc.update("update sync_task set next_run_at = now() where account_id = ? and status = 'PENDING'", accountId);
        jdbc.execute("delete from platform_throttle");
        syncService.execute(taskMapper.claimNext("worker-recent", UUID.randomUUID().toString(), 120, 1));
    }

    private PlatformAccount mine() {
        return accountService.listMine(userId).stream().filter(a -> a.getId().equals(accountId)).findFirst().orElseThrow();
    }

    private static Map<String, Object> item(PlatformAccount account, String item) {
        return account.getDataItems().stream().filter(row -> item.equals(row.get("item"))).findFirst().orElse(null);
    }

    private int stat(String metric) {
        return jdbc.queryForObject("select coalesce(sum(value), 0) from daily_stat where account_id = ? and metric = ?",
                Integer.class, accountId, metric);
    }

    @Test
    void totalsAndRecentDetailsArriveTogetherAndHistoryNeverCompletes() {
        run();

        PlatformAccount account = mine();
        assertThat(account.getSyncStatus()).isEqualTo("OK");
        assertThat(account.isHistoryComplete()).isFalse();
        assertThat(account.getPlatformSolvedTotal()).isEqualTo(42);
        assertThat(account.getCollectedProblems()).isEqualTo(2);
        assertThat(account.getTimedProblems()).isEqualTo(2);
        assertThat(item(account, "TOTALS")).containsEntry("status", "SUCCESS").containsEntry("observedCount", 42);
        assertThat(item(account, "HISTORY")).containsEntry("status", "SUCCESS").containsEntry("detail", NOTE);
        // 难度分布跟着快照存下来
        assertThat(jdbc.queryForObject("select raw_extra from total_snapshot where account_id = ? order by id desc limit 1",
                String.class, accountId)).contains("EASY");
        // 最近一次通过的时间不是首次通过：不计入"首次通过"，只计入当天通过
        assertThat(jdbc.queryForList("select distinct first_ac_confidence from user_solved_problem where account_id = ?",
                String.class, accountId)).containsExactly("EARLIEST_KNOWN");
        assertThat(stat("FIRST_AC")).isZero();
        assertThat(stat("UNIQUE_AC")).isEqualTo(2);
    }

    @Test
    void observedDeltaFromTotalsIsKept() {
        run();
        doReturn(totals(43)).when(leetcode).fetchTotals(any());
        run();

        assertThat(stat("OBSERVED_DELTA")).isEqualTo(1);
        assertThat(mine().getPlatformSolvedTotal()).isEqualTo(43);
    }

    @Test
    void totalsFailureStillSavesRecentDetailsAsPartial() {
        doThrow(new ApiException(ErrorKind.NOT_FOUND, "力扣没有返回该用户的题数")).when(leetcode).fetchTotals(any());

        run();

        PlatformAccount account = mine();
        assertThat(account.getSyncStatus()).isEqualTo("PARTIAL");
        assertThat(account.getCollectedProblems()).isEqualTo(2);
        assertThat(account.getPlatformSolvedTotal()).isNull();
        assertThat(item(account, "TOTALS")).containsEntry("status", "FAILED").containsEntry("errorKind", "NOT_FOUND");
        assertThat(item(account, "HISTORY")).containsEntry("status", "SUCCESS");
    }
}
