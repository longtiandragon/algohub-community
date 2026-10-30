package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.szpu.algohub.common.TimeSupport;

@Service
public class StatisticsService {

    private final StatisticsMapper statisticsMapper;
    private final DailyStatMapper dailyStatMapper;
    private final SubmissionMapper submissionMapper;

    public StatisticsService(StatisticsMapper statisticsMapper, DailyStatMapper dailyStatMapper,
                             SubmissionMapper submissionMapper) {
        this.statisticsMapper = statisticsMapper;
        this.dailyStatMapper = dailyStatMapper;
        this.submissionMapper = submissionMapper;
    }

    /**
     * 个人档案。各指标分开表达，不把“通过提交次数”当作“全部提交次数”，
     * 也不把观察增量当作首次 AC。
     */
    public Map<String, Object> profile(Long userId) {
        return profile(userId, false);
    }

    private Map<String, Object> profile(Long userId, boolean verifiedOnly) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> platformSolved = statisticsMapper.platformSolved(userId, verifiedOnly);
        long totalSolved = platformSolved.stream()
                .mapToLong(row -> ((Number) row.get("solved")).longValue())
                .sum();
        result.put("platformSolved", platformSolved);
        // 各平台分别去重后的合计：同一道题在多个平台各计一次。
        result.put("totalSolvedAcrossPlatforms", totalSolved);

        List<Map<String, Object>> accountTotals = statisticsMapper.accountSolvedTotals(userId);
        Map<String, Long> platformTotals = new LinkedHashMap<>();
        for (Map<String, Object> account : accountTotals) {
            platformTotals.merge(String.valueOf(account.get("platform")), toLong(account.get("solved")), Long::sum);
        }
        // Luogu browser/public records share problem IDs: duplicate problems across accounts count once.
        long luoguDetails = platformSolved.stream().filter(row -> "LUOGU".equals(row.get("platform")))
                .mapToLong(row -> toLong(row.get("solved"))).sum();
        long luoguSnapshot = accountTotals.stream().filter(row -> "LUOGU".equals(row.get("platform")))
                .mapToLong(row -> toLong(row.get("snapshotSolved"))).max().orElse(0);
        if (platformTotals.containsKey("LUOGU")) platformTotals.put("LUOGU", Math.max(luoguDetails, luoguSnapshot));
        result.put("totalPassed", platformTotals.values().stream().mapToLong(Long::longValue).sum());
        result.put("platformTotals", platformTotals.entrySet().stream()
                .map(entry -> Map.<String, Object>of("platform", entry.getKey(), "solved", entry.getValue()))
                .toList());

        // 跨平台映射只覆盖人工确认过的题目，因此同时给出去重结果与覆盖范围，
        // 未建立映射的部分仍按各平台分别计数，不声称是全球唯一题量。
        Map<String, Object> dedup = statisticsMapper.uniqueSolvedSummary(userId, verifiedOnly);
        long uniqueSolved = toLong(dedup.get("uniqueSolved"));
        long mappedSolved = toLong(dedup.get("mappedSolved"));
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("uniqueSolvedAfterMapping", uniqueSolved);
        mapping.put("mergedDuplicates", totalSolved - uniqueSolved);
        mapping.put("mappedSolvedRecords", mappedSolved);
        mapping.put("note", mappedSolved == 0
                ? "尚未建立任何跨平台题目映射，总量即各平台通过数合计"
                : "仅合并了人工确认的映射题目；未建立映射的题目仍按各平台分别计数");
        result.put("crossPlatformMapping", mapping);
        result.put("snapshotOverview", statisticsMapper.snapshotOverview(userId, verifiedOnly));
        result.put("difficultyDistribution", statisticsMapper.difficultyDistribution(userId, verifiedOnly));
        result.put("tagDistribution", statisticsMapper.tagDistribution(userId, verifiedOnly));
        result.put("tagCoverage", statisticsMapper.tagCoverage(userId, verifiedOnly));
        result.put("recentAccepted", submissionMapper.recentAccepted(userId, 20, verifiedOnly));
        return result;
    }

    /**
     * 给别人看的档案：snapshotOverview 只保留已验证归属的账号，并去掉托管方式（authMode，会暴露是否托管了登录态）
     * 与归属状态；showAccounts 为 false 时再去掉账号名（handle）与原始快照（rawExtra，里面可能带账号标识）。
     * 全部聚合与近期记录使用同一已验证账号范围，个人关注和待认领记录不对外给出。
     */
    public Map<String, Object> profileForOthers(Long userId, boolean showAccounts) {
        Map<String, Object> result = profile(userId, true);
        result.put("snapshotOverview", statisticsMapper.snapshotOverview(userId, true).stream()
                .filter(row -> "VERIFIED".equals(row.get("ownershipStatus")))
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.remove("authMode");
                    copy.remove("ownershipStatus");
                    if (!showAccounts) {
                        copy.remove("handle");
                        copy.remove("rawExtra");
                    }
                    return (Map<String, Object>) copy;
                })
                .toList());
        result.put("accountsHidden", !showAccounts);
        return result;
    }

    /** 账号状态轮询只读快照，避免重复聚合标签、难度与提交历史。 */
    public List<Map<String, Object>> snapshots(Long userId) {
        return statisticsMapper.snapshotOverview(userId);
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * 热力图数据。每个指标单独返回，前端需展示指标名称与口径。
     */
    public Map<String, Object> heatmap(Long userId, LocalDate from, LocalDate to, String platform) {
        return heatmap(userId, from, to, platform, false);
    }

    public Map<String, Object> heatmap(Long userId, LocalDate from, LocalDate to, String platform, boolean verifiedOnly) {
        List<Map<String, Object>> rows = dailyStatMapper.heatmap(userId, from, to, platform, verifiedOnly);
        Map<String, Map<String, Long>> byMetric = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String metric = String.valueOf(row.get("metric"));
            String date = String.valueOf(row.get("statDate"));
            long value = ((Number) row.get("value")).longValue();
            byMetric.computeIfAbsent(metric, k -> new LinkedHashMap<>())
                    .merge(date, value, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("timezone", TimeSupport.CAMPUS_ZONE.getId());
        result.put("metrics", byMetric);
        result.put("metricNotes", Map.of(
                "PLATFORM_ACTIVITY", "洛谷主页每日活动值，沿用源站日期；仅覆盖源站公开并已同步的日期，不代表首次 AC 或去重通过题数",
                "FIRST_AC", "首次通过该题的日期落在当天；仅在历史覆盖足以确认时提供",
                "UNIQUE_AC", "当天通过的题目去重，可能包含以前通过过的题",
                "AC_SUBMISSIONS", "当天的通过提交次数",
                "SUBMISSIONS", "当天的全部提交次数，含重复与未通过",
                "OBSERVED_DELTA", "只有总数的平台：两次同步之间观察到的增量，不是准确的首次 AC 日期"));
        return result;
    }
}
