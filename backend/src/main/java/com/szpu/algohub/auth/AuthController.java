package com.szpu.algohub.auth;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AuthDtos.LoginRequest;
import com.szpu.algohub.auth.AuthDtos.MeView;
import com.szpu.algohub.auth.AuthDtos.RegisterRequest;
import com.szpu.algohub.auth.AuthDtos.TokenView;
import com.szpu.algohub.auth.AuthService.SessionResult;
import com.szpu.algohub.auth.identity.GithubOauthService;
import com.szpu.algohub.auth.identity.IdentityMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 认证入口。访问令牌走 Authorization 头，刷新令牌走 HttpOnly Cookie，
 * 因此前端脚本读不到刷新令牌，也不需要把长期凭证存进 localStorage。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "algohub_refresh";

    private final AuthService authService;
    private final GithubOauthService githubOauthService;
    private final EmailLoginService emailLoginService;
    private final RefreshCookies cookies;
    private final OauthBrowserFlow flows;
    private final SecurityActionService actions;
    private final IdentitySecurity identities;
    private final AppUserMapper users;
    private final IdentityMapper bindings;
    private final AppProperties properties;
    private final PendingGithubLink pendingLinks;

    public AuthController(AuthService authService, GithubOauthService githubOauthService,
                          EmailLoginService emailLoginService, RefreshCookies cookies, OauthBrowserFlow flows,
                          SecurityActionService actions, IdentitySecurity identities, AppUserMapper users,
                          IdentityMapper bindings, AppProperties properties, PendingGithubLink pendingLinks) {
        this.pendingLinks = pendingLinks;
        this.flows = flows;
        this.actions = actions;
        this.identities = identities;
        this.users = users;
        this.bindings = bindings;
        this.properties = properties;
        this.cookies = cookies;
        this.authService = authService;
        this.githubOauthService = githubOauthService;
        this.emailLoginService = emailLoginService;
    }

    public record GithubCallbackRequest(String code, String state) {
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResult<TokenView>> register(@Valid @RequestBody RegisterRequest request,
                                                         HttpServletRequest http) {
        throw ApiException.forbidden("密码注册已关闭，请使用邮箱验证码或 GitHub 注册登录");
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<TokenView>> login(@Valid @RequestBody LoginRequest request,
                                                      HttpServletRequest http) {
        SessionResult session = authService.login(request.username(), request.password(),
                ip(http), userAgent(http));
        return withRefreshCookie(session);
    }

    @GetMapping("/email/status")
    public ApiResult<Map<String,Object>> emailStatus() {return ApiResult.ok(Map.of("enabled",emailLoginService.enabled()));}

    @PostMapping("/email/code")
    public ApiResult<Void> emailCode(@Valid @RequestBody EmailIdentityController.SendRequest request,HttpServletRequest http) {
        emailLoginService.send(request.email(),ip(http));return ApiResult.ok();
    }

    @PostMapping("/email/login")
    public ResponseEntity<ApiResult<TokenView>> emailLogin(@Valid @RequestBody EmailIdentityController.VerifyRequest request,HttpServletRequest http) {
        AppUser user=emailLoginService.verify(request.email(),request.code());
        return withRefreshCookie(authService.issueSession(user,ip(http),userAgent(http),"EMAIL_LOGIN_SUCCESS"));
    }

    /** 刷新访问令牌：刷新令牌一次性使用，成功后立即轮换。 */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResult<TokenView>> refresh(HttpServletRequest http) {
        SessionResult session = authService.refresh(readRefreshCookie(http), ip(http), userAgent(http));
        return withRefreshCookie(session);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(HttpServletRequest http) {
        authService.logout(currentUserOrNull(), readRefreshCookie(http), ip(http), userAgent(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .body(ApiResult.ok());
    }

    /** 退出所有设备：撤销全部刷新令牌，并让已签发的访问令牌立即失效。 */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResult<Map<String, Object>>> logoutAll(HttpServletRequest http) {
        LoginUser current = CurrentUser.require();
        int revoked = authService.logoutAll(current.getId(), ip(http), userAgent(http));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revokedSessions", revoked);
        result.put("note", "所有设备的登录状态已失效，需要重新登录");
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .body(ApiResult.ok(result));
    }

    /** 未登录时返回 data=null，前端据此展示访客状态。 */
    @GetMapping("/me")
    public ApiResult<MeView> me() {
        LoginUser user = currentUserOrNull();
        return ApiResult.ok(user == null ? null : authService.me(user.getId()));
    }

    @GetMapping("/sessions")
    public ApiResult<List<TokenStore.SessionInfo>> sessions(HttpServletRequest http) {
        return ApiResult.ok(authService.sessions(CurrentUser.requireId(), readRefreshCookie(http)));
    }

    @GetMapping("/events")
    public ApiResult<List<Map<String, Object>>> events(@RequestParam(defaultValue = "30") int limit) {
        return ApiResult.ok(authService.authEvents(CurrentUser.requireId(), com.szpu.algohub.common.Limits.clamp(limit, 200)));
    }

    // ---------- GitHub 绑定 / 登录 ----------

    @GetMapping("/github/status")
    public ApiResult<Map<String, Object>> githubStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", githubOauthService.isEnabled());
        result.put("note", githubOauthService.isEnabled()
                ? "可以直接用 GitHub 登录：第一次登录会自动创建账号，并用 GitHub 已验证的邮箱作为登录邮箱"
                : "未配置 GitHub OAuth：配置 client-id 与 client-secret 后重启启用（本地在 local-secret/github.properties，服务器在 production.env）");
        return ApiResult.ok(result);
    }

    /**
     * 生成授权链接。未登录时是 GitHub 登录；已登录时只能带 purpose，用已绑定的 GitHub 确认本人（敏感操作前）。
     * 已登录绑定 GitHub 走 /github/bind-url，要先确认本人。
     */
    @GetMapping("/github/authorize-url")
    public ResponseEntity<ApiResult<Map<String, Object>>> githubAuthorizeUrl(
            @RequestParam(required = false) String purpose) {
        LoginUser current = currentUserOrNull();
        Long userId = current == null ? null : current.getId();
        if (purpose != null) {
            CurrentUser.require();
            SecurityActionService.purpose(purpose);
        } else if (userId != null) {
            throw ApiException.forbidden("绑定 GitHub 前请先确认本人身份");
        }
        return beginGithub(userId, userId == null ? "LOGIN" : "REAUTH", purpose);
    }

    public record ActionRequest(String actionToken) {
    }

    public record GithubLinkLoginRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="email|password") String method,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=254) String email,
            @jakarta.validation.constraints.Pattern(regexp="[0-9]{6}") String code,
            @jakarta.validation.constraints.Size(max=32) String username,
            @jakarta.validation.constraints.Size(max=64) String password) {}

    @GetMapping("/github/link-context")
    @Transactional
    public ApiResult<Map<String,Object>> githubLinkContext(HttpServletRequest http) {
        return ApiResult.ok(pendingLinks.context(linkSecret(http),currentId()));
    }

    @PostMapping("/github/link-code")
    @Transactional
    public ApiResult<Void> githubLinkCode(HttpServletRequest http) {
        pendingLinks.send(linkSecret(http),currentId());
        return ApiResult.ok();
    }

    @PostMapping("/github/link-login")
    @Transactional(noRollbackFor=ApiException.class)
    public ResponseEntity<ApiResult<Map<String,Object>>> githubLinkLogin(
            @Valid @RequestBody GithubLinkLoginRequest request, HttpServletRequest http) {
        if (request.email()!=null || request.username()!=null)
            throw ApiException.validation("关联目标已锁定，不能指定其他邮箱或账号");
        var pending=pendingLinks.verify(linkSecret(http),currentId(),request.method(),request.code(),request.password(),ip(http),userAgent(http));
        var binding=githubOauthService.bind(pending.userId(),pending.github());
        authService.audit(pending.userId(),null,"OAUTH_LINKED",ip(http),userAgent(http),"GITHUB:"+binding.getLogin());
        identities.changed(pending.userId());
        var session=authService.reissueAfterIdentityChange(pending.userId(),ip(http),userAgent(http));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,refreshCookie(session.refreshToken()).toString(),linkCookie("",0).toString())
                .body(ApiResult.ok(Map.of("mode","BIND","token",session.view())));
    }

    private Long currentId() {
        var user=currentUserOrNull();return user==null?null:user.getId();
    }
    private String linkSecret(HttpServletRequest http) {
        if(http.getCookies()!=null) for(var cookie:http.getCookies())
            if(PendingGithubLink.COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    private ResponseCookie linkCookie(String value,long seconds) {
        return ResponseCookie.from(PendingGithubLink.COOKIE,value).httpOnly(true)
                .secure(properties.getSecurity().isSecureCookie()).sameSite("Lax")
                .path("/api/auth/github").maxAge(Duration.ofSeconds(seconds)).build();
    }

    /** 绑定 GitHub：先用确认本人拿到的凭证换授权链接 */
    @PostMapping("/github/bind-url")
    @Transactional
    public ResponseEntity<ApiResult<Map<String, Object>>> githubBindUrl(@RequestBody ActionRequest request) {
        long userId = CurrentUser.requireId();
        actions.consume(userId, "BIND_GITHUB", request.actionToken());
        return beginGithub(userId, "BIND", null);
    }

    /** 授权链接 + 绑定浏览器的 Cookie（只发往 /api/auth/github；Lax 才能在 GitHub 跳回来时带上） */
    private ResponseEntity<ApiResult<Map<String, Object>>> beginGithub(Long userId, String mode, String purpose) {
        Map<String, Object> result = githubOauthService.authorizeUrl(userId);
        String secret = flows.create((String) result.get("state"), userId, mode, purpose);
        ResponseCookie cookie = ResponseCookie.from(OauthBrowserFlow.COOKIE, secret)
                .httpOnly(true)
                .secure(properties.getSecurity().isSecureCookie())
                .sameSite("Lax")
                .path("/api/auth/github")
                .maxAge(Duration.ofMinutes(10))
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(ApiResult.ok(result));
    }

    @PostMapping("/github/callback")
    @Transactional
    public ResponseEntity<ApiResult<Map<String, Object>>> githubCallback(
            @RequestBody GithubCallbackRequest request, HttpServletRequest http) {
        String browser = null;
        if (http.getCookies() != null) {
            for (var cookie : http.getCookies()) {
                if (OauthBrowserFlow.COOKIE.equals(cookie.getName())) {
                    browser = cookie.getValue();
                }
            }
        }
        LoginUser current = currentUserOrNull();
        OauthBrowserFlow.Flow flow = flows.consume(request.state(), browser, current == null ? null : current.getId());
        Long[] stateUserId = new Long[1];
        GithubOauthService.GithubUser githubUser =
                githubOauthService.exchange(request.code(), request.state(), stateUserId);
        if (!Objects.equals(flow.userId(), stateUserId[0])) {
            throw ApiException.forbidden("授权身份不匹配，请重新发起");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if ("REAUTH".equals(flow.mode())) {
            // 确认本人：必须是这个账号已经绑定的那个 GitHub
            var existing = bindings.findBinding("GITHUB", githubUser.id());
            if (existing == null || !existing.getUserId().equals(flow.userId())) {
                throw ApiException.forbidden("请用当前账号已绑定的 GitHub 确认身份");
            }
            result.put("mode", "REAUTH");
            result.put("purpose", flow.purpose());
            result.put("actionToken", actions.grant(users.findById(flow.userId()), flow.purpose()));
            return ResponseEntity.ok(ApiResult.ok(result));
        }
        if (stateUserId[0] != null) {
            // 绑定流程：state 中记录了发起绑定的本地用户
            var binding = githubOauthService.bind(stateUserId[0], githubUser);
            authService.audit(stateUserId[0], null, "OAUTH_LINKED", ip(http), userAgent(http),
                    "GITHUB:" + binding.getLogin());
            // 多了一种登录方式：其他设备都退出，这台设备换一个新的登录状态
            identities.changed(stateUserId[0]);
            SessionResult session = authService.reissueAfterIdentityChange(stateUserId[0], ip(http), userAgent(http));
            result.put("token", session.view());
            result.put("mode", "BIND");
            result.put("binding", Map.of("provider", binding.getProvider(),
                    "login", binding.getLogin() == null ? "" : binding.getLogin(),
                    "profileUrl", binding.getProfileUrl() == null ? "" : binding.getProfileUrl()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                    .body(ApiResult.ok(result));
        }
        if (bindings.findBinding("GITHUB",githubUser.id())==null && githubUser.verifiedEmail()!=null
                && users.findByVerifiedEmail(githubUser.verifiedEmail())!=null) {
            result.put("mode","LINK_REQUIRED");
            String secret=pendingLinks.create(users.findByVerifiedEmail(githubUser.verifiedEmail()),githubUser);
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,linkCookie(secret,600).toString()).body(ApiResult.ok(result));
        }
        // 登录流程：绑定过的直接登录；第一次登录自动开户，之后和注册一样选择学校
        GithubOauthService.LoginResult login = githubOauthService.loginOrRegister(githubUser, ip(http), userAgent(http));
        SessionResult session = authService.issueSession(login.user(), ip(http), userAgent(http), "LOGIN_GITHUB");
        result.put("mode", "LOGIN");
        result.put("created", login.created());
        result.put("token", session.view());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(ApiResult.ok(result));
    }

    /** 解绑 GitHub：要先确认本人；解绑后其他设备都退出，这台设备换一个新的登录状态 */
    @PostMapping("/github/unbind")
    @Transactional
    public ResponseEntity<ApiResult<TokenView>> githubUnbind(@RequestBody ActionRequest request, HttpServletRequest http) {
        long userId = CurrentUser.requireId();
        actions.consume(userId, "UNBIND_GITHUB", request.actionToken());
        githubOauthService.unbind(userId, ip(http), userAgent(http));
        identities.changed(userId);
        return withRefreshCookie(authService.reissueAfterIdentityChange(userId, ip(http), userAgent(http)));
    }

    // ---------- 内部工具 ----------

    private ResponseEntity<ApiResult<TokenView>> withRefreshCookie(SessionResult session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(ApiResult.ok(session.view()));
    }

    private ResponseCookie refreshCookie(String value) {
        return cookies.create(value, authService.refreshTokenTtl());
    }

    private ResponseCookie clearedRefreshCookie() {
        return cookies.clear();
    }

    private String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (REFRESH_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private LoginUser currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser user ? user : null;
    }

    private String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
