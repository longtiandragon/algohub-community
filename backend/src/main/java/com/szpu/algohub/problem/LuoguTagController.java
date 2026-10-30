package com.szpu.algohub.problem;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import java.util.Map;
@RestController
@RequestMapping("/api/luogu/tags")
public class LuoguTagController {
    private final LuoguTagEnrichment service;
    public LuoguTagController(LuoguTagEnrichment service) { this.service=service; }
    @GetMapping("/progress")
    public ApiResult<Map<String,Object>> progress() { return ApiResult.ok(service.progress(CurrentUser.requireId())); }
}
