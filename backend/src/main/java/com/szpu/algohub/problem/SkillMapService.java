package com.szpu.algohub.problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;

/**
 * 算法地图：把「我通过的题」按统一标签分类摊开，看清练过什么、没练过什么。
 *
 * 这不是能力评估。它只回答两个可核对的问题：
 * 「这个方向题库里有多少题」和「其中我通过了多少」。
 * 覆盖率低可能只是该方向题量大或标签不完整，页面上照此说明，不折算成分数或等级。
 */
@Service
public class SkillMapService {

    /** 推荐未通过题目时，难度区间相对当前水平的放宽幅度，与练习推荐保持一致。 */
    private static final int BAND_ABOVE = 300;
    private static final int MAX_ITEMS = 50;

    private final SkillMapMapper mapper;
    private final RecommendationMapper recommendationMapper;

    public SkillMapService(SkillMapMapper mapper, RecommendationMapper recommendationMapper) {
        this.mapper = mapper;
        this.recommendationMapper = recommendationMapper;
    }

    public Map<String, Object> map(Long userId, String platformName) {
        Platform platform = resolvePlatform(userId, platformName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());
        result.put("platformName", platform.getDisplayName());

        List<Map<String, Object>> rows = mapper.categories(userId, platform.name());
        Map<String, Object> coverage = mapper.solvedCoverage(userId, platform.name());
        long solvedTotal = number(coverage == null ? null : coverage.get("solvedTotal"));
        long taggedSolved = number(coverage == null ? null : coverage.get("taggedSolved"));

        if (rows.isEmpty()) {
            result.put("categories", List.of());
            result.put("solvedTotal", solvedTotal);
            result.put("taggedSolved", 0L);
            result.put("note", platform.getDisplayName()
                    + " 目前没有可用的题目标签，这张地图无法绘制。"
                    + "标签要么来自平台公开数据，要么来自管理员维护的映射，两者都没有时不会猜测。");
            return result;
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            long catalogCount = number(row.get("catalogCount"));
            long solvedCount = number(row.get("solvedCount"));
            long solvedInCatalog = number(row.get("solvedInCatalog"));
            // 覆盖率的分子只能是题库里的题：Gym、近期比赛的题目通过了但不在题库中，
            // 把它们算进分子会得到一个分母根本没统计过的比例。
            item.put("coverage", catalogCount == 0 ? null
                    : Math.round(solvedInCatalog * 10000.0 / catalogCount) / 100.0);
            item.put("solvedOffCatalog", Math.max(0, solvedCount - solvedInCatalog));
            categories.add(item);
        }

        result.put("categories", categories);
        result.put("solvedTotal", solvedTotal);
        result.put("taggedSolved", taggedSolved);
        result.put("untaggedSolved", Math.max(0, solvedTotal - taggedSolved));
        result.put("note", "分类来自平台原始标签经统一映射得到（未命中映射的记为 UNMAPPED）。"
                + "一道题带多个标签时会在每个分类里各计一次，因此各分类之和大于题目总数。"
                + "覆盖率 = 我在题库里通过的题 / 题库里该分类的题；Gym 与近期比赛的题目不在题库中，"
                + "它们计入通过数但不计入覆盖率，题库没同步时不给覆盖率。"
                + "难度是平台原始值，只在同一平台内比较。覆盖率低不等于弱项。");
        return result;
    }

    /**
     * 没指定平台时，用「通过的题里带标签最多」的平台，而不是一律给 Codeforces；
     * 一道带标签的题都没有（新同学、或只绑了没有标签的平台）时才回到 Codeforces。
     */
    private Platform resolvePlatform(Long userId, String platformName) {
        if (StringUtils.hasText(platformName)) {
            return Platform.parse(platformName);
        }
        String best = mapper.platformWithMostTaggedSolves(userId);
        return StringUtils.hasText(best) ? Platform.parse(best) : Platform.CODEFORCES;
    }

    /** 分类详情：我通过的题 + 还没通过的题 + 通过题的难度分布。 */
    public Map<String, Object> category(Long userId, String platformName, String category, int limit) {
        Platform platform = resolvePlatform(userId, platformName);
        if (!StringUtils.hasText(category)) {
            throw ApiException.validation("请指定算法分类");
        }
        String key = category.trim();
        int size = Math.min(Math.max(limit, 1), MAX_ITEMS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());
        result.put("category", key);
        result.put("solved", mapper.solvedInCategory(userId, platform.name(), key, size));

        // 未通过的题按当前水平挑；没有可用难度记录时不假装算得出水平，只按热度给
        List<Integer> recent = recommendationMapper.recentSolvedDifficulties(userId, platform.name(), 50);
        Integer level = estimateLevel(recent);
        if (level == null) {
            result.put("todo", mapper.todoInCategory(userId, platform.name(), key, null, null, size));
            result.put("todoBasis", "还没有带难度的通过记录，这里按平台公开的通过人数排序，不按难度筛选");
        } else {
            int min = level - 200;
            int max = level + BAND_ABOVE;
            List<Map<String, Object>> todo =
                    mapper.todoInCategory(userId, platform.name(), key, min, max, size);
            if (todo.isEmpty()) {
                // 区间内没题就退回全难度，并说清楚退了一步，不假装区间里挑出来的
                todo = mapper.todoInCategory(userId, platform.name(), key, null, null, size);
                result.put("todoBasis", "难度 " + min + "～" + max + " 区间内没有未通过的题，"
                        + "这里放宽到该分类的全部难度，按通过人数排序");
            } else {
                result.put("todoBasis", "取最近 " + recent.size() + " 道有难度记录的通过题的第 75 百分位（"
                        + level + "），在 " + min + "～" + max + " 区间里挑还没通过的题");
            }
            result.put("todo", todo);
        }

        result.put("difficultyHistogram", mapper.difficultyHistogram(userId, platform.name(), key));
        result.put("note", "「已通过」含同步与导入的记录，导入数据的时间可能只是当前已知最早；"
                + "「还没通过」只从已同步题库里挑，题库没同步的平台给不出建议。");
        return result;
    }

    /** 与练习推荐一致：用最近通过题目的第 75 百分位代表“能啃下来的难度”。 */
    private Integer estimateLevel(List<Integer> recent) {
        List<Integer> values = recent.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (values.isEmpty()) {
            return null;
        }
        int index = (int) Math.floor(values.size() * 0.75);
        return values.get(Math.min(index, values.size() - 1));
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
