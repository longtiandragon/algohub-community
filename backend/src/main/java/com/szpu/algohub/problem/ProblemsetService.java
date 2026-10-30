package com.szpu.algohub.problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;

/**
 * 题库浏览。
 *
 * 只列**已同步进题库**的题：没触发过题库同步的平台在这里是空的，页面直说，
 * 不拿「同学做过的那几道」冒充题库。
 *
 * 「我是否通过」实时查通过记录；难度与通过人数是平台公开值，只在同一平台内比较，
 * 不做跨平台换算，也不按通过人数倒推「这题应该多难」。
 */
@Service
public class ProblemsetService {

    private static final Set<String> SORTS = Set.of("POPULAR", "DIFFICULTY_ASC", "DIFFICULTY_DESC", "KEY");
    private static final Set<String> STATUSES = Set.of("ALL", "SOLVED", "UNSOLVED");
    private static final int MAX_PAGE_SIZE = 50;
    /** 深翻页对 offset 查询不划算，也没人真的翻到第 500 页；到顶时明确提示。 */
    private static final int MAX_PAGE = 400;
    private static final int MAX_KEYWORD = 64;

    private final ProblemsetMapper mapper;

    public ProblemsetService(ProblemsetMapper mapper) {
        this.mapper = mapper;
    }

    public record Query(String platform, Integer minDifficulty, Integer maxDifficulty, boolean onlyRated,
                        String keyword, String category, String status, String sort, int page, int size) {
    }

    public Map<String, Object> search(Long userId, Query query) {
        if (query.page() < 1 || query.page() > MAX_PAGE) {
            throw ApiException.validation("页码需要在 1～" + MAX_PAGE + " 之间，请再缩小筛选范围");
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw ApiException.validation("单页条数为 1～" + MAX_PAGE_SIZE);
        }
        String platform = StringUtils.hasText(query.platform())
                ? Platform.parse(query.platform()).name() : null;
        String status = normalize(query.status(), STATUSES, "ALL",
                "状态只支持 ALL、SOLVED（已通过）或 UNSOLVED（未通过）");
        String sort = normalize(query.sort(), SORTS, "POPULAR",
                "排序只支持 POPULAR（通过人数）、DIFFICULTY_ASC、DIFFICULTY_DESC 或 KEY");
        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().strip() : null;
        if (keyword != null && keyword.length() > MAX_KEYWORD) {
            throw ApiException.validation("搜索词最多 " + MAX_KEYWORD + " 个字符");
        }
        String category = StringUtils.hasText(query.category()) ? query.category().strip() : null;
        Integer min = query.minDifficulty();
        Integer max = query.maxDifficulty();
        if (min != null && max != null && min > max) {
            throw ApiException.validation("难度下限不能高于上限");
        }
        // 指定了难度区间就意味着只看有难度的题，否则「未评级」会被区间条件直接过滤掉，
        // 用户却以为是筛没筛到——这里显式打开 onlyRated，页面上也照此说明。
        boolean onlyRated = query.onlyRated() || min != null || max != null;

        // 关键字里的 %、_、\ 按普通字符匹配（SQL 里 escape）
        keyword = com.szpu.algohub.common.SqlLike.escape(keyword);
        List<Map<String, Object>> rows = mapper.search(userId, platform, min, max, onlyRated, keyword,
                category, status, sort, query.size(), ((long) query.page() - 1) * query.size());
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("categories", splitCategories(row.get("categories")));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", mapper.count(userId, platform, min, max, onlyRated, keyword, category, status));
        result.put("page", query.page());
        result.put("size", query.size());
        result.put("onlyRated", onlyRated);
        result.put("catalog", mapper.catalogOverview(userId));
        result.put("note", "只列出已同步进题库的题目；没触发过题库同步的平台在这里是空的，"
                + "不拿同学做过的题冒充题库。「已通过」来自你自己的同步记录，"
                + "难度与通过人数是平台公开值，只在同一平台内比较，不做跨平台换算。"
                + (onlyRated ? "当前按难度筛选，因此没有难度的题目不在结果里。" : ""));
        return result;
    }

    private String normalize(String value, Set<String> allowed, String fallback, String message) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw ApiException.validation(message);
        }
        return upper;
    }

    private static List<String> splitCategories(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).sorted()
                .toList();
    }
}
