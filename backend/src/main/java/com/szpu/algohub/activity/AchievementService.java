package com.szpu.algohub.activity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.school.SchoolService;

/**
 * 成就与每日一题。
 *
 * 成就全部由已同步的数据实时推导，不另存“已解锁”记录：
 * 数据被重算、纠错或解绑后成就会跟着回退，不会留下对不上的历史标记。
 * 因此这里也不提供“解锁时间”，只给当前进度。
 */
@Service
public class AchievementService {

    /** 每日一题的默认难度区间与候选池大小（按 Codeforces 的原始 rating 语义）。 */
    private static final int DAILY_MIN = 1000;
    private static final int DAILY_MAX = 1600;
    private static final int DAILY_POOL = 400;

    private final AchievementMapper mapper;
    private final ActivityService activityService;
    private final SchoolService schoolService;

    public AchievementService(AchievementMapper mapper, ActivityService activityService,
                              SchoolService schoolService) {
        this.mapper = mapper;
        this.activityService = activityService;
        this.schoolService = schoolService;
    }

    public record Achievement(String code, String category, String name, String description,
                              boolean unlocked, long progress, long target, String progressText) {
    }

    /** 里程碑阈值：达到就点亮，未达到则显示距离下一档还差多少。 */
    private static final int[] SOLVE_TIERS = {10, 50, 100, 300, 500, 1000};
    private static final int[] STREAK_TIERS = {3, 7, 14, 30, 100};
    private static final int[] DIFFICULTY_TIERS = {1000, 1200, 1500, 1800, 2100, 2400};
    private static final int[] CATEGORY_TIERS = {3, 6, 10};

    public Map<String, Object> achievements(Long userId) {
        return achievements(userId, false);
    }

    public Map<String, Object> achievements(Long userId, boolean verifiedOnly) {
        List<Achievement> list = new ArrayList<>();

        int solved = mapper.totalSolved(userId, verifiedOnly);
        for (int tier : SOLVE_TIERS) {
            list.add(new Achievement("SOLVE_" + tier, "题量",
                    "通过 " + tier + " 题",
                    "各平台分别去重后的通过题数合计达到 " + tier + " 道",
                    solved >= tier, Math.min(solved, tier), tier,
                    solved + " / " + tier));
        }

        Map<String, Object> streak = activityService.streak(userId, verifiedOnly);
        long currentStreak = toLong(streak.get("currentStreak"));
        long longestStreak = toLong(streak.get("longestStreak"));
        for (int tier : STREAK_TIERS) {
            list.add(new Achievement("STREAK_" + tier, "坚持",
                    "连续刷题 " + tier + " 天",
                    "按 Asia/Shanghai 自然日计算的最长连续通过天数达到 " + tier + " 天",
                    longestStreak >= tier, Math.min(longestStreak, tier), tier,
                    "当前连续 " + currentStreak + " 天，历史最长 " + longestStreak + " 天"));
        }

        // 难度突破只在同一平台内比较，取各平台最高难度里的最大值作为进度
        List<Map<String, Object>> peaks = mapper.difficultyPeaks(userId, verifiedOnly);
        long bestDifficulty = peaks.stream()
                .map(row -> row.get("maxDifficulty"))
                .filter(java.util.Objects::nonNull)
                .mapToLong(v -> ((Number) v).longValue())
                .max()
                .orElse(0);
        for (int tier : DIFFICULTY_TIERS) {
            list.add(new Achievement("DIFFICULTY_" + tier, "难度",
                    "攻克 " + tier + " 难度",
                    "通过过原始难度不低于 " + tier + " 的题目（难度只在同一平台内比较，不做跨平台换算）",
                    bestDifficulty >= tier, Math.min(bestDifficulty, tier), tier,
                    bestDifficulty == 0 ? "暂无带难度的通过记录" : "已攻克最高 " + bestDifficulty));
        }

        List<Map<String, Object>> categories = mapper.categoryCoverage(userId, verifiedOnly);
        long mappedCategories = categories.stream()
                .filter(row -> !"UNMAPPED".equals(row.get("category")))
                .count();
        for (int tier : CATEGORY_TIERS) {
            list.add(new Achievement("CATEGORY_" + tier, "广度",
                    "覆盖 " + tier + " 个算法分类",
                    "通过的题目覆盖到 " + tier + " 个统一标签分类（标签覆盖本身可能不完整）",
                    mappedCategories >= tier, Math.min(mappedCategories, tier), tier,
                    mappedCategories + " / " + tier));
        }

        int platforms = mapper.platformsWithSolves(userId, verifiedOnly);
        list.add(new Achievement("PLATFORM_2", "广度", "在 2 个平台留下记录",
                "至少在两个平台有已同步的通过记录",
                platforms >= 2, Math.min(platforms, 2), 2, platforms + " / 2"));
        list.add(new Achievement("PLATFORM_3", "广度", "在 3 个平台留下记录",
                "至少在三个平台有已同步的通过记录",
                platforms >= 3, Math.min(platforms, 3), 3, platforms + " / 3"));

        int contests = mapper.contestsTouched(userId, verifiedOnly);
        list.add(new Achievement("CONTEST_5", "比赛", "做过 5 场比赛的题",
                "提交涉及到 5 场不同比赛的题目。注意：这只表示题目属于某场比赛，不代表正式参赛计分",
                contests >= 5, Math.min(contests, 5), 5, contests + " / 5"));
        list.add(new Achievement("CONTEST_20", "比赛", "做过 20 场比赛的题",
                "提交涉及到 20 场不同比赛的题目，同样不代表正式参赛计分",
                contests >= 20, Math.min(contests, 20), 20, contests + " / 20"));

        List<Achievement> unlocked = list.stream().filter(Achievement::unlocked).toList();
        List<Achievement> locked = list.stream().filter(a -> !a.unlocked()).toList();
        // 下一个目标：未解锁里进度比例最高的那个，给一个明确的努力方向
        Achievement next = locked.stream()
                .max(java.util.Comparator.comparingDouble(a -> a.target() == 0 ? 0 : (double) a.progress() / a.target()))
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unlockedCount", unlocked.size());
        result.put("totalCount", list.size());
        result.put("achievements", list);
        result.put("nextTarget", next);
        result.put("categoryCoverage", categories);
        result.put("difficultyPeaks", peaks);
        result.put("note", "成就全部由已同步的数据实时推导，不单独保存解锁记录；"
                + "数据被重算或账号解绑后会跟着回退。未同步的记录不计入。");
        return result;
    }

    // ---------- 每日一题 ----------

    /**
     * 每日一题：按日期确定性地从已同步题库中挑一道，全校看到的是同一题，方便一起讨论。
     * 这不是平台官方的每日一题，只是本站在题库里按日期取样。
     */
    public Map<String, Object> dailyProblem(Long userId, String platformName) {
        String platform = platformName == null || platformName.isBlank()
                ? "CODEFORCES" : platformName.trim().toUpperCase();
        LocalDate today = TimeSupport.today();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("platform", platform);

        List<Map<String, Object>> pool = mapper.dailyCandidates(platform, DAILY_MIN, DAILY_MAX, DAILY_POOL);
        if (pool.isEmpty()) {
            result.put("problem", null);
            result.put("note", "题库尚未同步或该难度区间内没有题目，管理员可在管理后台触发一次题库同步");
            return result;
        }
        int index = Math.floorMod(seed(today, platform), pool.size());
        Map<String, Object> chosen = new LinkedHashMap<>(pool.get(index));
        Long problemId = ((Number) chosen.get("id")).longValue();
        chosen.put("tags", mapper.problemTags(problemId, platform));

        result.put("problem", chosen);
        result.put("solvedByMe", mapper.solvedByUser(userId, problemId) > 0);
        // 学校只按服务端成员记录确定；没有学校时不给校内人数，而不是给 0
        Long schoolId = schoolService.approvedSchoolId(userId);
        result.put("campusSolvedCount", schoolId == null ? null : mapper.campusSolvedCount(schoolId, problemId));
        result.put("difficultyRange", Map.of("min", DAILY_MIN, "max", DAILY_MAX));
        result.put("note", "按日期从已同步题库里确定性选题（同一天全校相同），难度区间 "
                + DAILY_MIN + "–" + DAILY_MAX + "，按平台原始难度筛选。"
                + "这不是平台官方的每日一题。校内通过人数只统计本校参与校内展示的同学。");
        return result;
    }

    /** 用日期与平台名生成稳定种子：同一天同一平台永远选到同一题。 */
    private int seed(LocalDate date, String platform) {
        byte[] raw = (date.toString() + "|" + platform).getBytes(StandardCharsets.UTF_8);
        int hash = 17;
        for (byte b : raw) {
            hash = hash * 31 + b;
        }
        return hash;
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
