package com.szpu.algohub.admin;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.account.OwnershipAppealService;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 归属申诉审核：通过只转移已验证归属标记，双方的记录与登录态都不搬动。 */
@RestController
@RequestMapping("/api/admin/ownership-appeals")
public class AdminOwnershipAppealController {

    private final OwnershipAppealService service;

    public AdminOwnershipAppealController(OwnershipAppealService service) {
        this.service = service;
    }

    public record ReviewRequest(String note) {
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResult.ok(service.list(status));
    }

    @PostMapping("/{id}/approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequest request) {
        return ApiResult.ok(service.approve(CurrentUser.requireId(), id, request == null ? null : request.note()));
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Void> reject(@PathVariable Long id, @RequestBody(required = false) ReviewRequest request) {
        service.reject(CurrentUser.requireId(), id, request == null ? null : request.note());
        return ApiResult.ok();
    }
}
