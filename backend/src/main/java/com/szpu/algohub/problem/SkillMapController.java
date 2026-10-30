package com.szpu.algohub.problem;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 算法地图：按统一标签分类看自己的练习分布。只读当前登录用户的数据。 */
@RestController
@RequestMapping("/api/skill-map")
public class SkillMapController {

    private final SkillMapService skillMapService;

    public SkillMapController(SkillMapService skillMapService) {
        this.skillMapService = skillMapService;
    }

    /** 不传 platform 时，默认选你通过的题里带标签最多的那个平台，返回体里会写明用的是哪个。 */
    @GetMapping
    public ApiResult<Map<String, Object>> map(@RequestParam(required = false) String platform) {
        return ApiResult.ok(skillMapService.map(CurrentUser.requireId(), platform));
    }

    /** 某个分类下我通过的题、还没通过的题与难度分布。 */
    @GetMapping("/category")
    public ApiResult<Map<String, Object>> category(@RequestParam(required = false) String platform,
                                                   @RequestParam String category,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(skillMapService.category(CurrentUser.requireId(), platform, category, limit));
    }
}
