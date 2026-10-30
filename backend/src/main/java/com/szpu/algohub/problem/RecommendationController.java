package com.szpu.algohub.problem;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 练习推荐：从已同步题库里挑选未通过、难度略高于当前水平的题目。 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> recommend(
            @RequestParam(defaultValue = "CODEFORCES") String platform,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "6") int count) {
        return ApiResult.ok(recommendationService.recommend(CurrentUser.requireId(), platform, category, count));
    }

    /** 练习覆盖较少的标签分类，用于给出下一步方向。 */
    @GetMapping("/weak-categories")
    public ApiResult<Map<String, Object>> weakCategories(
            @RequestParam(defaultValue = "CODEFORCES") String platform,
            @RequestParam(defaultValue = "6") int limit) {
        return ApiResult.ok(recommendationService.weakCategories(CurrentUser.requireId(), platform, limit));
    }
}
