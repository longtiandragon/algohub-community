package com.szpu.algohub.account;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** 本人账号操作；管理员代操作须使用受模块权限和审计保护的 /api/admin 接口。 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LuoguBrowserImportService luoguBrowserImport;
    private final AuthService authService;

    public AccountController(AccountService accountService, LuoguBrowserImportService luoguBrowserImport, AuthService authService) {
        this.accountService = accountService;
        this.luoguBrowserImport = luoguBrowserImport;
        this.authService = authService;
    }

    public record BindRequest(@NotBlank String platform, @NotBlank String handle, String credential) {
    }

    /**
     * 登录态请求。cookie 必填；userAgent 与 headers 可选，
     * 用于需要与浏览器一致的 UA 或额外请求头（如 CSRF Token）的站点。
     * 兼容旧写法：只传 credential 时整体当作 Cookie。
     */
    public record CredentialRequest(String credential, String cookie, String userAgent,
                                    java.util.Map<String, String> headers) {
        public String effectiveCookie() {
            return cookie != null && !cookie.isBlank() ? cookie : credential;
        }
    }

    @GetMapping
    public ApiResult<List<PlatformAccount>> listMine() {
        return ApiResult.ok(accountService.listMine(CurrentUser.requireId()));
    }

    @PostMapping
    public ApiResult<PlatformAccount> bind(@Valid @RequestBody BindRequest request) {
        return ApiResult.ok(accountService.bind(CurrentUser.requireId(), request.platform(),
                request.handle(), request.credential()));
    }

    @PostMapping("/{id}/credential")
    public ApiResult<Void> saveCredential(@PathVariable Long id, @RequestBody CredentialRequest request) {
        accountService.saveCredential(id, CurrentUser.requireId(), false,
                request.effectiveCookie(), request.userAgent(), request.headers());
        return ApiResult.ok();
    }

    /** 试用登录态但不保存，先确认能否取到本人信息。 */
    @PostMapping("/{id}/credential/test")
    public ApiResult<java.util.Map<String, Object>> testCredential(@PathVariable Long id,
                                                                  @RequestBody CredentialRequest request) {
        return ApiResult.ok(accountService.testCredential(id, CurrentUser.requireId(), false,
                request.effectiveCookie(), request.userAgent(), request.headers()));
    }

    @DeleteMapping("/{id}/credential")
    public ApiResult<Void> deleteCredential(@PathVariable Long id) {
        accountService.deleteCredential(id, CurrentUser.requireId(), false);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/sync")
    public ApiResult<Void> sync(@PathVariable Long id) {
        accountService.triggerSync(id, CurrentUser.requireId(), false);
        return ApiResult.ok();
    }

    public record LuoguBeginRequest(boolean auto) {}

    @PostMapping("/{id}/luogu-browser-import/begin")
    public ApiResult<java.util.Map<String, String>> beginLuoguImport(@PathVariable Long id,
            @RequestBody(required = false) LuoguBeginRequest request) {
        return ApiResult.ok(luoguBrowserImport.begin(CurrentUser.requireId(), id, request != null && request.auto()));
    }

    @GetMapping("/{id}/luogu-browser-import/auto-status")
    public ApiResult<java.util.Map<String, Object>> luoguAutoStatus(@PathVariable Long id) {
        return ApiResult.ok(luoguBrowserImport.autoStatus(CurrentUser.requireId(), id));
    }

    @GetMapping("/luogu-extra")
    public ApiResult<List<java.util.Map<String, Object>>> luoguExtra() {
        return ApiResult.ok(luoguBrowserImport.extraSolved(CurrentUser.requireId()));
    }

    @GetMapping("/luogu-extra/{userId}")
    public ApiResult<List<java.util.Map<String, Object>>> publicLuoguExtra(@PathVariable Long userId) {
        Long viewerId = CurrentUser.requireId();
        authService.requireReadableProfile(userId, viewerId, authService.canReadPrivateProfiles(viewerId));
        var rows = luoguBrowserImport.extraSolved(userId, true);
        if (authService.privacy(userId).isShowAccounts()) return ApiResult.ok(rows);
        return ApiResult.ok(rows.stream().map(row -> {
            var copy = new java.util.LinkedHashMap<>(row);
            copy.remove("handle");
            return (java.util.Map<String, Object>) copy;
        }).toList());
    }

    @DeleteMapping("/{id}/luogu-browser-import/auto")
    public ApiResult<Void> revokeLuoguAuto(@PathVariable Long id) {
        luoguBrowserImport.revokeAuto(CurrentUser.requireId(), id);
        return ApiResult.ok();
    }

    @PostMapping("/sync-all")
    public ApiResult<AccountService.SyncAllResult> syncAll() {
        return ApiResult.ok(accountService.triggerAllSync(CurrentUser.requireId()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> unbind(@PathVariable Long id) {
        accountService.unbind(id, CurrentUser.requireId(), false);
        return ApiResult.ok();
    }
}
