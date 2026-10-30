package com.szpu.algohub.contest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedContest;
import com.szpu.algohub.platform.PlatformRegistry;

@Service
public class ContestService {

    private static final Logger log = LoggerFactory.getLogger(ContestService.class);

    private final PlatformRegistry registry;
    private final ContestMapper contestMapper;

    public ContestService(PlatformRegistry registry, ContestMapper contestMapper) {
        this.registry = registry;
        this.contestMapper = contestMapper;
    }

    /**
     * 刷新所有支持比赛列表的平台。单个平台失败不影响其他平台，页面仍展示上次成功的数据。
     */
    public Map<String, Object> refreshAll() { return refreshAll(Runnable::run); }

    public Map<String, Object> refreshAll(java.util.function.Consumer<Runnable> save) {
        int saved = 0;
        List<String> failures = new ArrayList<>();
        for (PlatformAdapter adapter : registry.all()) {
            if (!adapter.capability().contestList()) {
                continue;
            }
            List<FetchedContest> fetched;
            try {
                fetched = adapter.fetchContests();
            } catch (Exception e) {
                log.warn("{} 比赛刷新失败: {}", adapter.platform().getDisplayName(), e.getMessage());
                failures.add(adapter.platform().name() + ": " + e.getMessage());
                continue;
            }
            // Ownership loss must propagate, not be swallowed as an upstream failure.
            save.accept(() -> fetched.forEach(item -> contestMapper.upsert(toEntity(item))));
            saved += fetched.size();
        }
        save.accept(() -> contestMapper.deleteOlderThan(Instant.now().minus(Duration.ofDays(30))));
        return Map.of("saved", saved, "failures", failures);
    }

    private Contest toEntity(FetchedContest fetched) {
        Contest contest = new Contest();
        contest.setPlatform(fetched.platform().name());
        contest.setContestKey(fetched.contestKey());
        contest.setTitle(fetched.title());
        contest.setStartAt(fetched.startAt());
        contest.setEndAt(fetched.endAt());
        contest.setDurationSec(fetched.durationSec());
        contest.setStatus(fetched.status());
        contest.setCategory(fetched.category());
        contest.setUrl(fetched.url());
        contest.setSource(fetched.source());
        return contest;
    }

    public List<Contest> upcoming(String platform, String category, int limit) {
        // 保留最近开始的比赛，便于查看正在进行的场次。
        return contestMapper.findUpcoming(Instant.now().minus(Duration.ofHours(6)),
                platform, category, limit);
    }

    // ---------- 比赛收藏与日历导出 ----------

    /** 我收藏了哪些场次，用于给列表打标记。 */
    public java.util.Set<Long> favoritedIds(Long userId) {
        return new java.util.HashSet<>(contestMapper.favoritedIds(userId));
    }

    /** 收藏一场比赛。重复收藏是幂等的。 */
    public Map<String, Object> favorite(Long userId, Long contestId) {
        if (contestMapper.exists(contestId) == 0) {
            throw com.szpu.algohub.common.ApiException.notFound(
                    "找不到这场比赛，可能已经结束并被清理（比赛结束 30 天后清理）");
        }
        contestMapper.favorite(userId, contestId);
        return Map.of("contestId", contestId, "favorited", true);
    }

    public Map<String, Object> unfavorite(Long userId, Long contestId) {
        contestMapper.unfavorite(userId, contestId);
        return Map.of("contestId", contestId, "favorited", false);
    }

    public List<Contest> favorited(Long userId) {
        return contestMapper.findFavorited(userId);
    }

    /**
     * 把收藏的比赛导成 .ics。比赛信息实时取自 contest 表，
     * 所以源站改期后重新导出就是新的时间；已经导入日历的旧日程不会自己更新。
     */
    public byte[] calendar(Long userId) {
        List<Contest> favorited = contestMapper.findFavorited(userId);
        if (favorited.isEmpty()) {
            throw com.szpu.algohub.common.ApiException.validation(
                    "还没有收藏任何比赛，先在比赛日历里点星标再导出");
        }
        return ContestCalendar.build(favorited, Instant.now());
    }

    public List<String> categories() {
        return contestMapper.listCategories();
    }

    public Instant lastUpdatedAt() {
        return contestMapper.lastUpdatedAt();
    }

    public void updateCategory(Long id, String category) {
        contestMapper.updateCategory(id, category);
    }
}
