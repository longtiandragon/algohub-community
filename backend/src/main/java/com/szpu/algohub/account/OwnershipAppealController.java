package com.szpu.algohub.account;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 申请人查看、补充与撤回自己的归属申诉。申诉只能由核验成功自动生成，不能凭空提交。 */
@RestController
@RequestMapping("/api/ownership-appeals")
public class OwnershipAppealController {

    private final OwnershipAppealService service;

    public OwnershipAppealController(OwnershipAppealService service) {
        this.service = service;
    }

    public record ReasonRequest(String reason) {
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> mine() {
        return ApiResult.ok(service.mine(CurrentUser.requireId()));
    }

    @PostMapping("/{id}/reason")
    public ApiResult<Void> reason(@PathVariable Long id, @RequestBody ReasonRequest request) {
        service.updateReason(CurrentUser.requireId(), id, request.reason());
        return ApiResult.ok();
    }

    @PostMapping("/{id}/withdraw")
    public ApiResult<Void> withdraw(@PathVariable Long id) {
        service.withdraw(CurrentUser.requireId(), id);
        return ApiResult.ok();
    }
}
