package com.szpu.algohub.goal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;

/**
 * 个人周目标。
 *
 * 目标是自己定的，进度是平台数据算的，两者分开：
 * 进度只数**通过时间落在本周**的记录，不存快照、不做折算，
 * 重算统计或解绑账号后进度会跟着回退。
 *
 * 明确不做的事：不把跨平台难度折算成一个能力值，不把「没有通过时间的记录」
 * 硬算成本周完成（它们会单独列出来，让人知道平台没漏算，只是源站没给时间）。
 */
@Service
public class WeeklyGoalService {

    private static final int MAX_TARGET = 500;
    private static final int RECENT_WEEKS = 8;
    private static final int MAX_RECENT_PROBLEMS = 20;

    private final WeeklyGoalMapper mapper;

    public WeeklyGoalService(WeeklyGoalMapper mapper) {
        this.mapper = mapper;
    }

    public record SaveRequest(Integer target, String category, String platform) {
    }

    /** 本周（Asia/Shanghai）的周一。 */
    public static LocalDate currentWeekStart() {
        return TimeSupport.today().with(DayOfWeek.MONDAY);
    }

    public Map<String, Object> save(Long userId, SaveRequest request) {
        if (request == null || request.target() == null) {
            throw ApiException.validation("请填写本周想新通过的题数");
        }
        int target = request.target();
        if (target < 1 || target > MAX_TARGET) {
            throw ApiException.validation("目标题数需要在 1～" + MAX_TARGET + " 之间");
        }
        String category = StringUtils.hasText(request.category()) ? request.category().trim() : null;
        String platform = StringUtils.hasText(request.platform())
                ? Platform.parse(request.platform()).name() : null;

        mapper.upsert(userId, currentWeekStart(), target, category, platform);
        return current(userId);
    }

    public Map<String, Object> remove(Long userId) {
        int removed = mapper.remove(userId, currentWeekStart());
        Map<String, Object> result = new LinkedHashMap<>(current(userId));
        result.put("removed", removed > 0);
        return result;
    }

    /** 本周目标与进度，外加最近几周的完成情况。没设目标时也返回本周已通过题数。 */
    public Map<String, Object> current(Long userId) {
        LocalDate weekStart = currentWeekStart();
        Map<String, Object> goal = mapper.find(userId, weekStart);
        String category = goal == null ? null : (String) goal.get("category");
        String platform = goal == null ? null : (String) goal.get("platform");

        Instant from = TimeSupport.campusDayStart(weekStart);
        Instant until = TimeSupport.campusDayStart(weekStart.plusWeeks(1));
        int solved = mapper.solvedInWindow(userId, from, until, platform, category);
        int withoutTime = mapper.addedWithoutTime(userId, from, until, platform);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weekStart", weekStart);
        result.put("weekEnd", weekStart.plusDays(6));
        result.put("daysLeft", Math.max(0, weekStart.plusWeeks(1).toEpochDay() - TimeSupport.today().toEpochDay()));
        result.put("hasGoal", goal != null);
        result.put("target", goal == null ? null : goal.get("target"));
        result.put("category", category);
        result.put("platform", platform);
        result.put("solved", solved);
        if (goal != null) {
            int target = ((Number) goal.get("target")).intValue();
            result.put("remaining", Math.max(0, target - solved));
            result.put("reached", solved >= target);
            // 百分比只用于画进度条，超额时也如实给出超过 100 的原始比例由前端裁剪
            result.put("percent", target == 0 ? 0 : Math.round(solved * 1000.0 / target) / 10.0);
        }
        result.put("addedWithoutTime", withoutTime);
        result.put("recentProblems",
                mapper.solvedInWindowList(userId, from, until, platform, category, MAX_RECENT_PROBLEMS));
        result.put("history", history(userId, weekStart));
        result.put("timezone", "Asia/Shanghai");
        result.put("note", buildNote(category, platform, withoutTime));
        return result;
    }

    /** 往前数几周：每周的目标与实际完成数，用来看自己的节奏，不做趋势预测。 */
    private List<Map<String, Object>> history(Long userId, LocalDate weekStart) {
        List<Map<String, Object>> rows = mapper.recent(userId, weekStart, RECENT_WEEKS);
        List<Map<String, Object>> history = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            LocalDate start = toLocalDate(row.get("weekStart"));
            if (start == null) {
                continue;
            }
            String category = (String) row.get("category");
            String platform = (String) row.get("platform");
            int target = ((Number) row.get("target")).intValue();
            int solved = mapper.solvedInWindow(userId, TimeSupport.campusDayStart(start),
                    TimeSupport.campusDayStart(start.plusWeeks(1)), platform, category);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("weekStart", start);
            item.put("target", target);
            item.put("solved", solved);
            item.put("category", category);
            item.put("platform", platform);
            item.put("reached", solved >= target);
            history.add(item);
        }
        return history;
    }

    private String buildNote(String category, String platform, int withoutTime) {
        StringBuilder note = new StringBuilder();
        note.append("进度按「通过时间落在本周（Asia/Shanghai 周一至周日）」实时统计，不另存一份；")
                .append("同一题只算一次，重复提交不加数。");
        if (category != null) {
            note.append("本周只统计「").append(category).append("」方向的题，")
                    .append("分类来自平台原始标签经统一映射，标签不完整时会有遗漏。");
        }
        if (platform != null) {
            note.append("本周只统计 ").append(platform).append(" 的题。");
        }
        if (withoutTime > 0) {
            note.append("另有 ").append(withoutTime)
                    .append(" 道本周入库但源站没给通过时间的题（例如洛谷题单、Tracker 缓存），")
                    .append("它们不计入本周进度，也没有被丢掉，可以在「刷题历史与导出」里看到。");
        }
        note.append("新通过的题要等对应账号同步之后才会出现，不是实时的。");
        return note.toString();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return null;
    }
}
