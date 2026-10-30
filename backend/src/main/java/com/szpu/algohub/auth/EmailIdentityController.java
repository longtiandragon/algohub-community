package com.szpu.algohub.auth;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/me/login-email")
public class EmailIdentityController {
    private final EmailIdentityService service;
    private final SecurityActionService actions;
    private final AuthService auth;
    private final AppUserMapper users;
    private final RefreshCookies cookies;
    /** 注册后这段时间内第一次绑登录邮箱（新人引导那一步）不用再确认本人 */
    private static final java.time.Duration FRESH_ACCOUNT = java.time.Duration.ofMinutes(30);
    public EmailIdentityController(EmailIdentityService service, SecurityActionService actions, AuthService auth,
                                   AppUserMapper users, RefreshCookies cookies) {
        this.service = service;
        this.actions = actions;
        this.auth = auth;
        this.users = users;
        this.cookies = cookies;
    }
    public record SendRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record VerifyRequest(@NotBlank @Email @Size(max=254) String email, @NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record BindingVerifyRequest(@NotBlank @Email @Size(max=254) String email, @NotBlank @Pattern(regexp="[0-9]{6}") String code, @Pattern(regexp="[0-9]{6}") String previousCode, @Size(max=100) String actionToken) {}
    @GetMapping public ApiResult<Map<String,Object>> status() { return ApiResult.ok(service.status(CurrentUser.requireId())); }
    @PostMapping("/code") public ApiResult<Void> send(@Valid @RequestBody SendRequest request) { service.send(CurrentUser.requireId(),request.email());return ApiResult.ok(); }
    /**
     * 第一次绑登录邮箱等于多了一种登录方式，要先确认本人（刚注册的新账号除外）；换邮箱时核对旧邮箱的验证码。
     * 换邮箱后其他设备都退出，这台设备换一个新的登录状态（响应里带新的访问令牌）。
     */
    @PostMapping("/verify")
    @org.springframework.transaction.annotation.Transactional(noRollbackFor = com.szpu.algohub.common.ApiException.class)
    public org.springframework.http.ResponseEntity<ApiResult<AuthDtos.TokenView>> verify(
            @Valid @RequestBody BindingVerifyRequest request, jakarta.servlet.http.HttpServletRequest http) {
        long userId = CurrentUser.requireId();
        if (service.status(userId).get("email") == null && !freshAccount(userId)) {
            actions.consume(userId, "FIRST_EMAIL", request.actionToken());
        }
        if (!service.verify(userId, request.email(), request.code(), request.previousCode())) {
            return org.springframework.http.ResponseEntity.ok(ApiResult.ok(null));
        }
        var session = auth.reissueAfterIdentityChange(userId, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.SET_COOKIE,
                        cookies.create(session.refreshToken(), auth.refreshTokenTtl()).toString())
                .body(ApiResult.ok(session.view()));
    }

    private boolean freshAccount(long userId) {
        AppUser user = users.findById(userId);
        return user != null && user.getCreatedAt() != null
                && user.getCreatedAt().isAfter(java.time.Instant.now().minus(FRESH_ACCOUNT));
    }
    @DeleteMapping public ApiResult<Void> remove() {service.remove(CurrentUser.requireId());return ApiResult.ok();}
}
