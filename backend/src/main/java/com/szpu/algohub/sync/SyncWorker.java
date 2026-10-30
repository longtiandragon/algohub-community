package com.szpu.algohub.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.statistics.SubmissionMapper;

import jakarta.annotation.PreDestroy;

/**
 * 本机后台同步：受限线程池 + 数据库任务表。关闭后端后定时同步停止，重启后按租约恢复：
 * 执行中的任务定期续租，进程退出就不再续租，租约过期后被重新领取（换新的执行编号）。
 */
@Component
@ConditionalOnProperty(prefix = "algohub.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncService syncService;
    private final SyncTaskMapper taskMapper;
    private final PlatformAccountMapper accountMapper;
    private final SubmissionMapper submissionMapper;
    private final AppProperties properties;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    /** 执行中的任务：任务 id → 执行编号，用于续租。 */
    private final Map<Long, String> running = new ConcurrentHashMap<>();
    private final String owner;

    public SyncWorker(SyncService syncService, SyncTaskMapper taskMapper,
                      PlatformAccountMapper accountMapper, SubmissionMapper submissionMapper,
                      AppProperties properties) {
        this.syncService = syncService;
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.submissionMapper = submissionMapper;
        this.properties = properties;
        this.owner = "worker-" + ProcessHandle.current().pid();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "algohub-sync");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(properties.getSync().getWorkerThreads(), factory);
    }

    @Scheduled(fixedDelayString = "${algohub.sync.poll-interval-ms:5000}")
    public void poll() {
        int capacity = properties.getSync().getWorkerThreads() - inFlight.get();
        for (int i = 0; i < capacity; i++) {
            SyncTask task = taskMapper.claimNext(owner, UUID.randomUUID().toString(),
                    properties.getSync().getLeaseSeconds(), properties.getSync().getPerPlatformConcurrency());
            if (task == null) {
                return;
            }
            inFlight.incrementAndGet();
            running.put(task.getId(), task.getRunToken());
            executor.submit(() -> {
                try {
                    syncService.execute(task);
                } catch (Exception e) {
                    log.error("任务执行异常: {}", e.toString());
                } finally {
                    running.remove(task.getId(), task.getRunToken());
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /**
     * 续租（每 1/4 租约一次）：长任务（多页回填、慢站点）在执行期间保持租约。续租失败说明已被接管或已结束，
     * 不再为它续租；它最后的保存会因执行编号不符被拒绝。
     */
    @Scheduled(fixedDelayString = "#{${algohub.sync.lease-seconds:120} * 250}", initialDelay = 5000)
    public void heartbeat() {
        running.forEach((taskId, token) -> {
            if (taskMapper.renewLease(taskId, token, properties.getSync().getLeaseSeconds()) == 0) {
                running.remove(taskId, token);
                log.info("同步任务 {} 已不再由本次执行持有，停止续租", taskId);
            }
        });
    }

    /** 按活跃度安排自动同步：活跃账号更频繁，不活跃账号降频。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    public void scheduleDueAccounts() {
        Instant now = Instant.now();
        Instant idleBefore = now.minus(Duration.ofMinutes(properties.getSync().getIdleAccountIntervalMinutes()));
        Instant activeBefore = now.minus(Duration.ofMinutes(properties.getSync().getActiveAccountIntervalMinutes()));
        Instant activeWindow = now.minus(Duration.ofDays(properties.getSync().getActiveWindowDays()));

        for (PlatformAccount account : accountMapper.findDueForSync(idleBefore)) {
            syncService.enqueueAccountSync(account.getId(), account.getPlatform(), 120);
        }
        for (PlatformAccount account : accountMapper.findDueForSync(activeBefore)) {
            if ("LUOGU".equals(account.getPlatform()) || submissionMapper.countRecent(account.getId(), activeWindow) > 0) {
                syncService.enqueueAccountSync(account.getId(), account.getPlatform(), 100);
            }
        }
    }

    @Scheduled(fixedDelayString = "#{${algohub.sync.contest-interval-minutes:45} * 60 * 1000}",
            initialDelay = 15 * 1000)
    public void scheduleContestRefresh() {
        syncService.enqueueContestRefresh();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
