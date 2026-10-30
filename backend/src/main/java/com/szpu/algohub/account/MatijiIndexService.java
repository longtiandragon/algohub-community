package com.szpu.algohub.account;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.Redaction;
import com.szpu.algohub.platform.adapter.MatijiAdapter;
import com.szpu.algohub.platform.adapter.MatijiAdapter.Candidate;
import com.szpu.algohub.platform.adapter.MatijiAdapter.RankPage;

import jakarta.annotation.PreDestroy;

/**
 * 码蹄集昵称索引。
 *
 * 码蹄集没有按昵称查人的接口，排行榜是唯一的公开来源。这里把排行榜上看到过的账号都记下来，所有同学共享：
 * - 按昵称查找时先查索引，立即给出全部同名候选，不必每次从第一页翻起；
 * - 需要时再实时翻排行榜，翻到的每一页也整页写进索引；
 * - 后台低频逐页补全（默认每 10 分钟 10 页，受平台共享限速约束），翻完一轮从头再来。
 * 找到并绑定后一律按数字 UID 同步，不再翻排行榜。索引没覆盖到的排名显示"暂未找到"，不是 0 题。
 */
@Service
public class MatijiIndexService {

    private static final Logger log = LoggerFactory.getLogger(MatijiIndexService.class);

    private final MatijiAdapter adapter;
    private final MatijiIndexMapper mapper;
    private final IdentityLookupGuard lookupGuard;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "matiji-index");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean crawling = new AtomicBoolean();

    @Value("${algohub.sync.enabled:true}")
    private boolean enabled;
    @Value("${algohub.matiji-index.pages-per-run:10}")
    private int pagesPerRun;

    public MatijiIndexService(MatijiAdapter adapter, MatijiIndexMapper mapper, IdentityLookupGuard lookupGuard,
                              PlatformTransactionManager transactionManager) {
        this.adapter = adapter;
        this.mapper = mapper;
        this.lookupGuard = lookupGuard;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /** 只查索引，不请求外站。 */
    public Map<String, Object> lookup(String nickname) {
        String name = normalize(nickname);
        Map<String, Object> result = coverage();
        result.put("candidates", mapper.findByNickname(name));
        result.put("fromIndex", true);
        return result;
    }

    /** 实时翻一页排行榜：整页写进索引，返回这一页里的同名候选。每页计入本人的查询限频。 */
    public Map<String, Object> scan(Long userId, String nickname, int start) {
        String name = normalize(nickname);
        lookupGuard.acquire(userId);
        // 外站请求在事务之外
        RankPage page = adapter.rankPage(start);
        save(page);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", page.rows().stream().filter(row -> name.equals(row.nickname())).toList());
        result.put("nextStart", page.nextStart());
        result.put("total", page.total());
        result.put("hasMore", page.hasMore());
        result.put("fromIndex", false);
        return result;
    }

    /** 索引覆盖情况：已记下多少账号、排行榜总人数、上一轮从头开始翻的时间。 */
    public Map<String, Object> coverage() {
        Map<String, Object> state = mapper.state();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexed", mapper.count());
        result.put("total", state == null ? null : state.get("total"));
        result.put("roundStarted", state == null ? null : state.get("roundStarted"));
        result.put("lastRunAt", state == null ? null : state.get("lastRunAt"));
        return result;
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void scheduleCrawl() {
        if (!enabled || pagesPerRun <= 0 || !crawling.compareAndSet(false, true)) {
            return;
        }
        // 翻页要排队等平台限速，放在自己的线程里，不占调度线程
        executor.submit(() -> {
            try {
                crawl(pagesPerRun);
            } finally {
                crawling.set(false);
            }
        });
    }

    /** 从上次停下的位置接着翻若干页；翻到末尾就结束本轮，下一次从头开始。 */
    void crawl(int pages) {
        Map<String, Object> state = mapper.state();
        int start = state == null || state.get("nextStart") == null ? 0 : ((Number) state.get("nextStart")).intValue();
        Integer total = state == null || state.get("total") == null ? null : ((Number) state.get("total")).intValue();
        for (int i = 0; i < pages; i++) {
            RankPage page;
            try {
                page = adapter.rankPage(start);
            } catch (ApiException e) {
                String reason = e.getMessage() == null ? "请求失败" : Redaction.redact(e.getMessage());
                mapper.saveState(start, total, false, reason.substring(0, Math.min(200, reason.length())));
                log.info("码蹄集昵称索引本轮暂停：{}", e.getMessage());
                return;
            }
            save(page);
            total = page.total();
            boolean roundDone = !page.hasMore();
            start = roundDone ? 0 : page.nextStart();
            mapper.saveState(start, total, start == 0, null);
            if (roundDone) {
                return;
            }
        }
    }

    private void save(RankPage page) {
        tx.executeWithoutResult(status -> {
            for (Candidate row : page.rows()) {
                mapper.upsert(row.userId(), row.nickname(), row.school(), row.passNum(), row.rankOrder());
            }
        });
    }

    private static String normalize(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.strip().length() > 100) {
            throw ApiException.validation("请输入不超过 100 字的完整昵称");
        }
        return nickname.strip();
    }
}
