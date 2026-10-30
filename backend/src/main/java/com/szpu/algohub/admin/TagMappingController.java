package com.szpu.algohub.admin;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.problem.TagMappingMapper;

/** 由 /api/admin/** 的安全规则限制为管理员；原始题目标签始终保留。 */
@RestController
@RequestMapping("/api/admin/tag-mappings")
public class TagMappingController {
    private final TagMappingMapper mapper;
    public TagMappingController(TagMappingMapper mapper) { this.mapper = mapper; }

    public record MappingRequest(String platform, String tagRaw, String category) {}

    @GetMapping
    public ApiResult<List<Map<String,Object>>> list() { return ApiResult.ok(mapper.list()); }

    @PostMapping
    public ApiResult<Void> save(@RequestBody MappingRequest request) {
        String platform = Platform.parse(request.platform()).name();
        String tag = valid(request.tagRaw(),128,"原始标签");
        String category = valid(request.category(),64,"统一分类");
        mapper.save(platform,tag,category);
        return ApiResult.ok();
    }

    @DeleteMapping
    public ApiResult<Void> remove(@RequestParam String platform, @RequestParam String tagRaw) {
        mapper.remove(Platform.parse(platform).name(), valid(tagRaw,128,"原始标签"));
        return ApiResult.ok();
    }

    private static String valid(String value, int max, String label) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw ApiException.validation(label + "不能为空且不能超过 " + max + " 字符");
        }
        return value.strip();
    }
}
