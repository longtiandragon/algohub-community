package com.szpu.algohub.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * 同步任务的执行编号、续租与"核对后才写"。验收口径：
 * 模拟外站超时、后端重启和任务被接管，不能重复计数、回退游标或覆盖新任务的结果。
 *
 * 旧执行"还在抓取时被接管"用适配器替身模拟：第一次抓取期间让租约过期、由新执行领取并跑完，
 * 再把旧执行抓到的（更旧的）结果交回去。
 */
@SpringBootTest
class SyncFencingTest {

    private static final long LEASE = 120;

    @Autowired
    private SyncService syncService;
    @Autowired
    private SyncTaskMapper taskMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PlatformRegistry registry;

    private final PlatformAdapter history = mock(PlatformAdapter.class);
    private final PlatformAdapter totalsOnly = mock(PlatformAdapter.class);
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from sync_task");
        jdbc.execute("delete from platform_throttle");
        userId = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name) values (?, 'x', 'fence') returning id
                """, Long.class, "fence-" + UUID.randomUUID());
        when(registry.require(any())).thenReturn(history);
        when(registry.require(Platform.LEETCODE_CN)).thenReturn(totalsOnly);
        when(history.capability()).thenReturn(new PlatformCapability(Platform.CODEFORCES, true, true, true, false,
                "OFFICIAL", "OFFICIAL", false, "test"));
        when(totalsOnly.capability()).thenReturn(new PlatformCapability(Platform.LEETCODE_CN, true, false, true,
                false, "OFFICIAL", "NONE", false, "test"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
        jdbc.execute("delete from problem where problem_key like 'FENCE-%'");
        jdbc.execute("delete from sync_task");
        jdbc.execute("delete from platform_throttle");
    }

    private Long account(String platform, String uid) {
        return jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, supports_history)
                values (?, ?, ?, ?, 'VERIFIED', true) returning id
                """, Long.class, userId, platform, uid, uid);
    }

    private SyncTask claim(String owner) {
        return taskMapper.claimNext(owner, UUID.randomUUID().toString(), LEASE, 1);
    }

    private void expireLease(Long taskId) {
        jdbc.update("update sync_task set lease_until = now() - interval '1 second' where id = ?", taskId);
    }

    private static FetchedSubmission sub(int n) {
        return new FetchedSubmission("fence-" + n,
                new FetchedProblem("FENCE-" + n, "题 " + n, null, null, null, null, List.of(), false),
                "OK", true, Instant.parse("2026-09-0" + n + "T00:00:00Z"), "EXACT", null, null);
    }

    private static SubmissionPage page(String cursor, boolean hasMore, FetchedSubmission... submissions) {
        return new SubmissionPage(List.of(submissions), cursor, hasMore, !hasMore);
    }

    private String cursor(Long accountId) {
        List<String> values = jdbc.queryForList("""
                select cursor_value from sync_cursor where account_id = ? and cursor_kind = 'SUBMISSION_FORWARD'
                """, String.class, accountId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void staleExecutionCannotCommitAfterTakeover() {
        Long accountId = account("CODEFORCES", "fence-uid");
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask stale = claim("worker-a");
        AtomicReference<SyncTask> newer = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        when(history.fetchSubmissions(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                // 旧执行还在抓取：租约过期，新执行接管并跑完
                expireLease(stale.getId());
                SyncTask takeover = claim("worker-b");
                newer.set(takeover);
                syncService.execute(takeover);
                return page("2", false, sub(1), sub(2));
            }
            return page("3", false, sub(1), sub(2), sub(3));
        });

        syncService.execute(stale);

        SyncTask takeover = newer.get();
        assertThat(takeover.getId()).isEqualTo(stale.getId());
        assertThat(takeover.getRunToken()).isNotEqualTo(stale.getRunToken());
        // 新执行的结果为准：游标没有被回退，提交没有被重复计数
        assertThat(cursor(accountId)).isEqualTo("3");
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(3);
        assertThat(count("select count(*) from user_solved_problem where account_id = ?", accountId)).isEqualTo(3);
        SyncTask finished = taskMapper.findById(stale.getId());
        assertThat(finished.getStatus()).isEqualTo("SUCCESS");
        assertThat(finished.getRunToken()).isEqualTo(takeover.getRunToken());
        assertThat(jdbc.queryForObject("select sync_status from platform_account where id = ?", String.class,
                accountId)).isEqualTo("OK");
    }

    @Test
    void staleFailureCannotOverwriteNewerResult() {
        Long accountId = account("CODEFORCES", "fence-uid");
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask stale = claim("worker-a");
        AtomicInteger calls = new AtomicInteger();
        when(history.fetchSubmissions(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                expireLease(stale.getId());
                syncService.execute(claim("worker-b"));
                throw new ApiException(ErrorKind.NETWORK, "Codeforces 请求超时");
            }
            return page("3", false, sub(1), sub(2), sub(3));
        });

        syncService.execute(stale);

        // 旧执行的超时不能把新执行成功的任务与账号改成失败
        assertThat(taskMapper.findById(stale.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForMap("select sync_status, last_error_kind from platform_account where id = ?", accountId))
                .containsEntry("sync_status", "OK").containsEntry("last_error_kind", null);
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(3);
    }

    @Test
    void upstreamTimeoutKeepsOldDataAndRetriesLater() {
        Long accountId = account("CODEFORCES", "fence-uid");
        when(history.fetchSubmissions(any())).thenReturn(page("2", false, sub(1), sub(2)));
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        syncService.execute(claim("worker-a"));
        assertThat(cursor(accountId)).isEqualTo("2");

        when(history.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.NETWORK, "Codeforces 请求超时"));
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask retry = claim("worker-a");
        syncService.execute(retry);

        SyncTask failed = taskMapper.findById(retry.getId());
        assertThat(failed.getStatus()).isEqualTo("PENDING");
        assertThat(failed.getErrorKind()).isEqualTo("NETWORK");
        assertThat(failed.getNextRunAt()).isAfter(Instant.now().plusSeconds(60));
        assertThat(jdbc.queryForObject("select last_error_kind from platform_account where id = ?", String.class,
                accountId)).isEqualTo("NETWORK");
        // 旧数据原样保留，不因失败清零或回退
        assertThat(cursor(accountId)).isEqualTo("2");
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(2);
        assertThat(count("select count(*) from user_solved_problem where account_id = ?", accountId)).isEqualTo(2);
        // 退避期内不会被立即重复领取
        assertThat(claim("worker-a")).isNull();
    }

    @Test
    void restartRecoversAbandonedRunningTaskWithNewToken() {
        Long accountId = account("CODEFORCES", "fence-uid");
        when(history.fetchSubmissions(any())).thenReturn(page("2", false, sub(1), sub(2)));
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        // 旧进程领取后崩溃：不再续租，也不再执行
        SyncTask crashed = claim("worker-old");
        assertThat(claim("worker-new")).as("租约有效期内不能被接管").isNull();

        expireLease(crashed.getId());
        SyncTask recovered = claim("worker-new");

        assertThat(recovered.getId()).isEqualTo(crashed.getId());
        assertThat(recovered.getRunToken()).isNotEqualTo(crashed.getRunToken());
        assertThat(recovered.getAttempt()).isEqualTo(2);
        syncService.execute(recovered);
        assertThat(taskMapper.findById(recovered.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(2);
        // 崩溃前的执行即使"复活"也无权再写
        syncService.execute(crashed);
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(2);
    }

    @Test
    void heartbeatOnlyRenewsTheCurrentExecution() {
        Long accountId = account("CODEFORCES", "fence-uid");
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask running = claim("worker-a");
        jdbc.update("update sync_task set lease_until = now() + interval '5 seconds' where id = ?", running.getId());

        assertThat(taskMapper.renewLease(running.getId(), running.getRunToken(), LEASE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select extract(epoch from lease_until - now())::bigint from sync_task where id = ?
                """, Long.class, running.getId())).isGreaterThan(100);
        assertThat(taskMapper.renewLease(running.getId(), "not-mine", LEASE)).isZero();

        expireLease(running.getId());
        SyncTask takeover = claim("worker-b");
        assertThat(taskMapper.renewLease(running.getId(), running.getRunToken(), LEASE)).isZero();
        assertThat(taskMapper.renewLease(takeover.getId(), takeover.getRunToken(), LEASE)).isEqualTo(1);
    }

    @Test
    void unbindDuringFetchDiscardsResults() {
        Long accountId = account("CODEFORCES", "fence-uid");
        when(history.fetchSubmissions(any())).thenAnswer(invocation -> {
            jdbc.update("delete from platform_account where id = ?", accountId);
            return page("2", false, sub(1), sub(2));
        });
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask task = claim("worker-a");

        syncService.execute(task);

        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isZero();
        assertThat(count("select count(*) from sync_cursor where account_id = ?", accountId)).isZero();
        assertThat(taskMapper.findById(task.getId())).isNull();
    }

    @Test
    void identityChangeDuringFetchDiscardsResultsAndRequeues() {
        Long accountId = account("CODEFORCES", "fence-uid");
        when(history.fetchSubmissions(any())).thenAnswer(invocation -> {
            jdbc.update("update platform_account set platform_uid = 'fence-uid-2' where id = ?", accountId);
            return page("2", false, sub(1), sub(2));
        });
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);
        SyncTask task = claim("worker-a");

        syncService.execute(task);

        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isZero();
        assertThat(taskMapper.findById(task.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void samePlatformRunsOneTaskAtATimeWhileOthersProceed() {
        Long cf1 = account("CODEFORCES", "fence-cf-1");
        Long cf2 = account("CODEFORCES", "fence-cf-2");
        Long at = account("ATCODER", "fence-at");
        syncService.enqueueAccountSync(cf1, "CODEFORCES", 10);
        syncService.enqueueAccountSync(cf2, "CODEFORCES", 20);
        syncService.enqueueAccountSync(at, "ATCODER", 30);

        SyncTask first = claim("worker-a");
        SyncTask second = claim("worker-b");
        assertThat(first.getAccountId()).isEqualTo(cf1);
        assertThat(second.getAccountId()).as("CF 已有任务在跑，先领其他平台").isEqualTo(at);
        assertThat(claim("worker-c")).isNull();

        when(history.fetchSubmissions(any())).thenReturn(page("1", false, sub(1)));
        syncService.execute(first);
        assertThat(claim("worker-c").getAccountId()).isEqualTo(cf2);
    }

    @Test
    void rateLimitCoolsDownTheWholePlatformAndKeepsOldData() {
        Long cf1 = account("CODEFORCES", "fence-cf-1");
        Long cf2 = account("CODEFORCES", "fence-cf-2");
        Long at = account("ATCODER", "fence-at");
        when(history.fetchSubmissions(any())).thenReturn(page("1", false, sub(1)));
        syncService.enqueueAccountSync(cf1, "CODEFORCES", 10);
        syncService.execute(claim("worker-a"));

        when(history.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.RATE_LIMITED, "Codeforces 返回限流(429)"));
        syncService.enqueueAccountSync(cf1, "CODEFORCES", 10);
        syncService.execute(claim("worker-a"));

        assertThat(jdbc.queryForObject("""
                select extract(epoch from blocked_until - now())::bigint from platform_throttle where platform = 'CODEFORCES'
                """, Long.class)).isBetween(SyncService.PLATFORM_COOL_DOWN_SECONDS - 10, SyncService.PLATFORM_COOL_DOWN_SECONDS);
        assertThat(count("select count(*) from submission where account_id = ?", cf1)).isEqualTo(1);

        // 同平台的其他账号也先不领，其他平台照常
        syncService.enqueueAccountSync(cf2, "CODEFORCES", 5);
        syncService.enqueueAccountSync(at, "ATCODER", 50);
        assertThat(claim("worker-b").getAccountId()).isEqualTo(at);
        assertThat(claim("worker-c")).isNull();
    }

    @Test
    void repeatedRequestsMergeIntoOneActiveTask() {
        Long accountId = account("CODEFORCES", "fence-uid");
        assertThat(syncService.enqueueAccountSync(accountId, "CODEFORCES", 10)).isTrue();
        assertThat(syncService.enqueueAccountSync(accountId, "CODEFORCES", 10)).isFalse();
        SyncTask running = claim("worker-a");
        assertThat(syncService.enqueueAccountSync(accountId, "CODEFORCES", 10)).as("执行中同样合并").isFalse();
        assertThat(count("""
                select count(*) from sync_task where account_id = ? and status in ('PENDING', 'RUNNING')
                """, accountId)).isEqualTo(1);
        assertThat(running).isNotNull();
    }

    @Test
    void multiPageBackfillContinuesTheSameTaskWithFreshTokens() {
        Long accountId = account("CODEFORCES", "fence-uid");
        when(history.fetchSubmissions(any())).thenReturn(page("p1", true, sub(1)), page("p2", false, sub(2)));
        syncService.enqueueAccountSync(accountId, "CODEFORCES", 10);

        SyncTask first = claim("worker-a");
        syncService.execute(first);
        SyncTask continued = taskMapper.findById(first.getId());
        assertThat(continued.getStatus()).isEqualTo("PENDING");
        assertThat(continued.getAttempt()).isZero();

        jdbc.update("update sync_task set next_run_at = now() where id = ?", first.getId());
        SyncTask second = claim("worker-a");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getRunToken()).isNotEqualTo(first.getRunToken());
        syncService.execute(second);

        assertThat(taskMapper.findById(first.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(count("select count(*) from sync_task where account_id = ?", accountId)).isEqualTo(1);
        assertThat(cursor(accountId)).isEqualTo("p2");
        assertThat(count("select count(*) from submission where account_id = ?", accountId)).isEqualTo(2);
    }

    @Test
    void staleTotalsExecutionDoesNotAddAnotherSnapshot() {
        Long accountId = account("LEETCODE_CN", "fence-lc");
        syncService.enqueueAccountSync(accountId, "LEETCODE_CN", 10);
        SyncTask stale = claim("worker-a");
        AtomicInteger calls = new AtomicInteger();
        when(totalsOnly.fetchTotals(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                expireLease(stale.getId());
                syncService.execute(claim("worker-b"));
                return new TotalsSnapshot(40, null, Map.of(), "stale");
            }
            return new TotalsSnapshot(42, null, Map.of(), "fresh");
        });

        syncService.execute(stale);

        assertThat(jdbc.queryForList("select solved_total from total_snapshot where account_id = ?", Integer.class,
                accountId)).containsExactly(42);
        assertThat(taskMapper.findById(stale.getId()).getStatus()).isEqualTo("SUCCESS");
    }
    @Test
    void staleGlobalCatalogCannotOverwriteNewMetadata() {
        syncService.enqueueCatalogRefresh("CODEFORCES");
        SyncTask old=claim("old-catalog");
        AtomicInteger calls=new AtomicInteger();
        when(history.fetchProblemCatalog()).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(calls.incrementAndGet()==1) {
                expireLease(old.getId());
                syncService.execute(claim("new-catalog"));
                return List.of(catalogItem("old title", "old-tag"));
            }
            return List.of(catalogItem("new title", "new-tag"));
        });
        syncService.execute(old);
        assertThat(jdbc.queryForObject("select title from problem where platform='CODEFORCES' and problem_key='FENCE-CATALOG'",String.class)).isEqualTo("new title");
        assertThat(jdbc.queryForList("select tag_raw from problem_tag t join problem p on p.id=t.problem_id where p.problem_key='FENCE-CATALOG'",String.class)).containsExactly("new-tag");
        assertThat(taskMapper.findById(old.getId()).getStatus()).isEqualTo("SUCCESS");
    }

    private com.szpu.algohub.platform.PlatformDtos.CatalogProblem catalogItem(String title,String tag) {
        return new com.szpu.algohub.platform.PlatformDtos.CatalogProblem(
            new FetchedProblem("FENCE-CATALOG",title,null,"1200","CF 1200","OFFICIAL",List.of(tag),true),1200,10);
    }

    @Test
    void staleContestRefreshCannotOverwriteNewSchedule() {
        when(registry.all()).thenReturn(List.of(history));
        when(history.platform()).thenReturn(Platform.CODEFORCES);
        when(history.capability()).thenReturn(new PlatformCapability(Platform.CODEFORCES,true,true,true,false,"OFFICIAL","OFFICIAL",true,"test"));
        syncService.enqueueContestRefresh();
        SyncTask old=claim("old-contests");
        AtomicInteger calls=new AtomicInteger();
        when(history.fetchContests()).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String title="new schedule";
            if(calls.incrementAndGet()==1) {
                expireLease(old.getId());syncService.execute(claim("new-contests"));title="old schedule";
            }
            return List.of(new com.szpu.algohub.platform.PlatformDtos.FetchedContest(Platform.CODEFORCES,
                "FENCE-CONTEST",title,Instant.now().plusSeconds(3600),null,7200,"UPCOMING",null,null,"OFFICIAL"));
        });
        try {
            syncService.execute(old);
            assertThat(jdbc.queryForObject("select title from contest where platform='CODEFORCES' and contest_key='FENCE-CONTEST'",String.class)).isEqualTo("new schedule");
            assertThat(taskMapper.findById(old.getId()).getStatus()).isEqualTo("SUCCESS");
        } finally { jdbc.update("delete from contest where contest_key='FENCE-CONTEST'"); }
    }}
