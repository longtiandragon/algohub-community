package com.szpu.algohub.account;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts/{id}/qoj-browser-import")
public class QojBrowserAccountController {
    private final QojBrowserImportService service;
    public QojBrowserAccountController(QojBrowserImportService service) { this.service = service; }
    public record Begin(boolean auto) {}
    @PostMapping("/begin")
    public ApiResult<Map<String,String>> begin(@PathVariable Long id, @RequestBody(required=false) Begin request) {
        return ApiResult.ok(service.begin(CurrentUser.requireId(), id, request != null && request.auto()));
    }
    @GetMapping("/auto-status")
    public ApiResult<Map<String,Object>> status(@PathVariable Long id) {
        return ApiResult.ok(service.autoStatus(CurrentUser.requireId(), id));
    }
    @DeleteMapping("/auto")
    public ApiResult<Void> revoke(@PathVariable Long id) {
        service.revokeAuto(CurrentUser.requireId(), id);
        return ApiResult.ok();
    }
}
