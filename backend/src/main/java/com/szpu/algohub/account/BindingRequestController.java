package com.szpu.algohub.account;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.account.BindingRequestService.VerifyResult;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** 绑定申请：查询确认身份 → 签发验证串 → 核验公开资料 → 正式绑定。 */
@RestController
@RequestMapping("/api/binding-requests")
public class BindingRequestController {

    private final BindingRequestService service;

    public BindingRequestController(BindingRequestService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank String platform, @NotBlank String handle) {
    }

    /** 只查询平台公开资料并返回与当前用户的关系，不创建记录。 */
    @GetMapping("/lookup")
    public ApiResult<Map<String, Object>> lookup(@RequestParam String platform, @RequestParam String handle) {
        return ApiResult.ok(service.lookup(CurrentUser.requireId(), platform, handle));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@Valid @RequestBody CreateRequest body) {
        return ApiResult.ok(service.create(CurrentUser.requireId(), body.platform(), body.handle()));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(service.list(CurrentUser.requireId()));
    }

    @PostMapping("/{id}/verify")
    public ApiResult<VerifyResult> verify(@PathVariable Long id) {
        return ApiResult.ok(service.verify(CurrentUser.requireId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> cancel(@PathVariable Long id) {
        service.cancel(CurrentUser.requireId(), id);
        return ApiResult.ok();
    }
}
