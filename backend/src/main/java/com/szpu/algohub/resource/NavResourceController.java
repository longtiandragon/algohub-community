package com.szpu.algohub.resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/resources")
public class NavResourceController {

    private final NavResourceMapper mapper;

    public NavResourceController(NavResourceMapper mapper) {
        this.mapper = mapper;
    }

    /** 首页导航，按分类分组返回；未登录也可浏览。 */
    @GetMapping
    public ApiResult<Map<String, List<NavResource>>> list() {
        Map<String, List<NavResource>> grouped = new LinkedHashMap<>();
        for (NavResource resource : mapper.findAll(false)) {
            grouped.computeIfAbsent(resource.getCategory(), k -> new java.util.ArrayList<>()).add(resource);
        }
        return ApiResult.ok(grouped);
    }
}
