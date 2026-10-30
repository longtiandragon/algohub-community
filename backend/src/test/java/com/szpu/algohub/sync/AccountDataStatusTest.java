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
import com.szpu.algohub.account.CredentialCipher;
import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.PlatformCredentialMapper;
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
 * 数据项状态验收：总量成功、历史失败显示"部分成功"；没取过、取失败了与平台说是零严格区分。
 */
@SpringBootTest
class AccountDataStatusTest {

    @Autowired
    private SyncService syncService;
    @Autowired
    private SyncTaskMapper taskMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CredentialCipher cipher;
    @Autowired
    private PlatformCredentialMapper credentials;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PlatformRegistry registry;

    private final PlatformAdapter luogu = mock(PlatformAdapter.class);
    private final PlatformAdapter leetcode = mock(PlatformAdapter.class);
    private final PlatformAdapter qoj = mock(PlatformAdapter.class);
    private final PlatformAdapter nowcoder = mock(PlatformAdapter.class);
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from sync_task");
        jdbc.execute("delete from platform_throttle");
        userId = jdbc.queryForObject("insert into app_user (username, password_hash, display_name) values (?, 'x', 'data') returning id",
                Long.class, "data-status-" + UUID.randomUUID());
        when(registry.require(Platform.LUOGU)).thenReturn(luogu);
        when(registry.require(Platform.LEETCODE_CN)).thenReturn(leetcode);
        when(registry.require(Platform.QOJ)).thenReturn(qoj);
        when(registry.require(Platform.NOWCODER)).thenReturn(nowcoder);
        when(registry.find(any())).thenAnswer(inv -> java.util.Optional.of(registry.require(inv.getArgument(0))));
        when(luogu.capability()).thenReturn(new PlatformCapability(Platform.LUOGU, true, true, true, false,
                "OFFICIAL", "OFFICIAL", false, "test"));
        when(leetcode.capability()).thenReturn(new PlatformCapability(Platform.LEETCODE_CN, true, false, true, false,
                "OFFICIAL", "NONE", false, "test"));
        when(qoj.capability()).thenReturn(new PlatformCapability(Platform.QOJ, false, true, true, true,
                "NONE", "NONE", false, "test"));
        when(nowcoder.capability()).thenReturn(new PlatformCapability(Platform.NOWCODER, true, true, true, false,
                "NONE", "NONE", false, "test"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
        jdbc.execute("delete from problem where problem_key like 'DATA-%'");
        jdbc.execute("delete from sync_task");
    }

    private Long account(String platform) {
        return jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, supports_history)
                values (?, ?, ?, 'data', 'RESOLVED', true) returning id
                """, Long.class, userId, platform, "100001");
    }

    private void run(Long accountId, String platform) {
        syncService.enqueueAccountSync(accountId, platform, 10);
        // 上一轮失败后的退避期视为已过去
        jdbc.update("update sync_task set next_run_at = now() where account_id = ? and status = 'PENDING'", accountId);
        jdbc.execute("delete from platform_throttle");
        SyncTask task = taskMapper.claimNext("worker-data", UUID.randomUUID().toString(), 120, 1);
        syncService.execute(task);
    }

    private static FetchedProblem problem(String key) {
        return new FetchedProblem("DATA-" + key, "题 " + key, null, null, null, null, List.of(), false);
    }

    private PlatformAccount mine(Long accountId) {
        return accountService.listMine(userId).stream().filter(a -> a.getId().equals(accountId)).findFirst().orElseThrow();
    }

    private static Map<String, Object> item(PlatformAccount account, String item) {
        return account.getDataItems().stream().filter(row -> item.equals(row.get("item"))).findFirst().orElse(null);
    }

    @Test
    void publicTotalsSucceedHistoryFailsIsPartialNotFailure() {
        Long accountId = account("LUOGU");
        credentials.save(accountId, cipher.encrypt("cookie=expired"));
        when(luogu.fetchTotals(any())).thenReturn(new TotalsSnapshot(42, null, Map.of(), "public",
                List.of(problem("1"), problem("2"))));
        when(luogu.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.PARSE_ERROR, "洛谷未返回有效 AC 评测分页"));

        run(accountId, "LUOGU");

        PlatformAccount account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("PARTIAL");
        assertThat(account.getLastErrorKind()).isEqualTo("PARSE_ERROR");
        assertThat(account.getPlatformSolvedTotal()).isEqualTo(42);
        assertThat(account.getCollectedProblems()).isEqualTo(2);
        assertThat(account.getTimedProblems()).isZero();
        assertThat(item(account, "TOTALS")).containsEntry("status", "SUCCESS").containsEntry("observedCount", 42);
        assertThat(item(account, "SOLVED_LIST")).containsEntry("status", "SUCCESS").containsEntry("observedCount", 2);
        assertThat(item(account, "HISTORY")).containsEntry("status", "FAILED").containsEntry("errorKind", "PARSE_ERROR");
        assertThat(item(account, "HISTORY").get("lastSuccessAt")).isNull();
    }

    @Test
    void expiredOptionalCredentialIsSetAsideAndPublicDataKeepsSyncing() {
        Long accountId = account("LUOGU");
        credentials.save(accountId, cipher.encrypt("cookie=expired"));
        when(luogu.fetchTotals(any())).thenReturn(new TotalsSnapshot(42, null, Map.of(), "public", List.of(problem("1"))));
        when(luogu.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.CREDENTIAL_INVALID, "登录态已失效"));

        run(accountId, "LUOGU");

        PlatformAccount account = mine(accountId);
        // 可选登录态失效：不暂停整个账号，只把登录态放一边
        assertThat(account.getSyncStatus()).isEqualTo("PARTIAL");
        assertThat(account.getCredentialStatus()).isEqualTo("INVALID");
        assertThat(item(account, "HISTORY")).containsEntry("status", "NEEDS_CREDENTIAL");

        // 下一轮不再拿失效的登录态请求历史，公开数据照常更新
        doReturn(new TotalsSnapshot(43, null, Map.of(), "public", List.of(problem("1")))).when(luogu).fetchTotals(any());
        run(accountId, "LUOGU");
        account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("OK");
        assertThat(account.getPlatformSolvedTotal()).isEqualTo(43);
        assertThat(item(account, "HISTORY")).containsEntry("status", "NEEDS_CREDENTIAL");
    }

    @Test
    void requiredCredentialFailurePausesTheAccount() {
        Long accountId = account("QOJ");
        credentials.save(accountId, cipher.encrypt("UOJSESSID=expired"));
        when(qoj.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 返回登录页面"));

        run(accountId, "QOJ");

        PlatformAccount account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("PAUSED");
        assertThat(account.getCredentialStatus()).isEqualTo("INVALID");
        assertThat(item(account, "HISTORY")).containsEntry("status", "NEEDS_CREDENTIAL");
    }

    @Test
    void unknownFailureAndTrueZeroAreDistinct() {
        Long unknown = account("LEETCODE_CN");
        PlatformAccount never = mine(unknown);
        assertThat(never.getPlatformSolvedTotal()).as("从没取得过：未知，不是 0").isNull();
        assertThat(never.getDataItems()).isEmpty();

        when(leetcode.fetchTotals(any())).thenThrow(new ApiException(ErrorKind.NETWORK, "力扣请求超时"));
        run(unknown, "LEETCODE_CN");
        PlatformAccount failed = mine(unknown);
        assertThat(failed.getSyncStatus()).isEqualTo("FAILED");
        assertThat(failed.getPlatformSolvedTotal()).as("取失败：仍是未知，不写成 0").isNull();
        assertThat(item(failed, "TOTALS")).containsEntry("status", "FAILED").containsEntry("errorKind", "NETWORK");

        doReturn(new TotalsSnapshot(0, null, Map.of("EASY", 0), null)).when(leetcode).fetchTotals(any());
        run(unknown, "LEETCODE_CN");
        PlatformAccount zero = mine(unknown);
        assertThat(zero.getSyncStatus()).isEqualTo("OK");
        assertThat(zero.getPlatformSolvedTotal()).as("平台确认是零").isZero();
        assertThat(item(zero, "TOTALS")).containsEntry("status", "SUCCESS").containsEntry("observedCount", 0);

        // 之后再失败：保留上次成功的数量与时间，只把状态改成失败
        doThrow(new ApiException(ErrorKind.UPSTREAM_ERROR, "力扣源站返回 502")).when(leetcode).fetchTotals(any());
        run(unknown, "LEETCODE_CN");
        PlatformAccount again = mine(unknown);
        assertThat(again.getPlatformSolvedTotal()).isZero();
        assertThat(item(again, "TOTALS")).containsEntry("status", "FAILED").containsEntry("observedCount", 0);
        assertThat(item(again, "TOTALS").get("lastSuccessAt")).isNotNull();
    }

    @Test
    void historySucceedsTotalsFailsIsAlsoPartial() {
        Long accountId = account("NOWCODER");
        when(nowcoder.fetchSubmissions(any())).thenReturn(new SubmissionPage(List.of(new FetchedSubmission("n-1",
                problem("N1"), "AC", true, Instant.parse("2026-09-01T00:00:00Z"), "EXACT", null, null)), null, false, true));
        when(nowcoder.fetchTotals(any())).thenThrow(new ApiException(ErrorKind.PARSE_ERROR, "牛客练习主页结构变化"));

        run(accountId, "NOWCODER");

        PlatformAccount account = mine(accountId);
        assertThat(account.getSyncStatus()).isEqualTo("PARTIAL");
        assertThat(account.getTimedProblems()).isEqualTo(1);
        assertThat(item(account, "HISTORY")).containsEntry("status", "SUCCESS");
        assertThat(item(account, "TOTALS")).containsEntry("status", "FAILED");
        assertThat(account.getPlatformSolvedTotal()).isNull();
    }
}
