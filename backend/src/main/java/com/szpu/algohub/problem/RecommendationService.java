package com.szpu.algohub.problem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;

/**
 * 练习推荐。
 *
 * 做法很简单，也只敢声称这么多：
 * 取用户最近通过题目的原始难度分布，定一个略高于当前水平的区间，
 * 在**已同步的题库**里挑出还没通过、且落在该区间的题，按平台公开的通过人数排序后随机取若干道。
 *
 * 它不是能力评估，也不是官方推荐；难度只在同一平台内比较，不做跨平台换算。
 */
@Service
public class RecommendationService {

    /** 推荐区间相对当前水平的偏移，单位是平台原始难度值。 */
    private static final int BAND_BELOW = 0;
    private static final int BAND_ABOVE = 300;
    private static final int CANDIDATE_POOL = 120;

    private final RecommendationMapper mapper;

    public RecommendationService(RecommendationMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> recommend(Long userId, String platformName, String category, int count) {
        Platform platform = Platform.parse(platformName);
        int wanted = Math.min(Math.max(count, 1), 20);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());

        int catalogSize = mapper.catalogSize(platform.name());
        result.put("catalogSize", catalogSize);
        if (catalogSize == 0) {
            result.put("items", List.of());
            result.put("note", platform.getDisplayName() + " 的题库还没有同步，管理员可在管理后台触发一次题库同步");
            return result;
        }

        List<Integer> recent = mapper.recentSolvedDifficulties(userId, platform.name(), 50);
        Integer level = estimateLevel(recent);
        int min;
        int max;
        if (level == null) {
            // 没有可用难度记录时，给一个明确标注的入门区间，而不是假装算出了水平
            min = 800;
            max = 1200;
            result.put("levelBasis", "没有带难度的通过记录，使用入门区间作为起点");
        } else {
            min = Math.max(0, level - BAND_BELOW);
            max = level + BAND_ABOVE;
            result.put("levelBasis", "取最近 " + recent.size() + " 道有难度记录的通过题目的第 75 百分位（"
                    + level + "），向上放宽 " + BAND_ABOVE);
        }
        result.put("difficultyRange", Map.of("min", min, "max", max));

        String categoryFilter = StringUtils.hasText(category) ? category.trim() : null;
        List<Map<String, Object>> pool = mapper.candidates(userId, platform.name(), min, max,
                categoryFilter, CANDIDATE_POOL);
        if (pool.isEmpty()) {
            result.put("items", List.of());
            result.put("note", "该难度区间内没有找到未通过的题目，可以换个标签分类，或等题库下次同步后再看");
            return result;
        }

        // 先按热度取候选池，再随机选取，避免每次打开都是同一批题
        List<Map<String, Object>> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        List<Map<String, Object>> items = new ArrayList<>(shuffled.subList(0, Math.min(wanted, shuffled.size())));
        for (Map<String, Object> item : items) {
            Long problemId = ((Number) item.get("id")).longValue();
            item.put("tags", mapper.problemTags(problemId, platform.name()));
        }
        items.sort((a, b) -> Integer.compare(
                a.get("difficultyValue") == null ? 0 : ((Number) a.get("difficultyValue")).intValue(),
                b.get("difficultyValue") == null ? 0 : ((Number) b.get("difficultyValue")).intValue()));

        result.put("items", items);
        result.put("category", categoryFilter);
        result.put("note", "从已同步题库中挑选：排除你已通过的题，难度用平台原始值筛选，"
                + "按平台公开的通过人数排序后随机取样。这不是能力评估，也不是官方推荐。");
        return result;
    }

    /** 用最近通过题目的第 75 百分位作为当前练习水平，比平均值更能反映“能啃下来的难度”。 */
    private Integer estimateLevel(List<Integer> recent) {
        List<Integer> values = recent.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (values.isEmpty()) {
            return null;
        }
        int index = (int) Math.floor(values.size() * 0.75);
        return values.get(Math.min(index, values.size() - 1));
    }

    /** 练得较少的标签分类：题库里题量不少、但自己做得比例低的方向。 */
    public Map<String, Object> weakCategories(Long userId, String platformName, int limit) {
        Platform platform = Platform.parse(platformName);
        if (mapper.catalogSize(platform.name()) == 0) {
            throw new ApiException(com.szpu.algohub.common.ErrorKind.NOT_FOUND,
                    platform.getDisplayName() + " 的题库尚未同步，无法计算练习覆盖比例");
        }
        List<Map<String, Object>> rows = mapper.weakCategories(userId, platform.name(),
                Math.min(Math.max(limit, 1), 20));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());
        result.put("items", rows);
        result.put("note", "按「已通过 / 题库题量」的比例从低到高排列，只统计题库中题量不少于 20 道的分类；"
                + "标签覆盖本身可能不完整，比例低不代表一定是弱项。");
        return result;
    }
}
