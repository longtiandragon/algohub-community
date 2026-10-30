package com.szpu.algohub.demo;

import java.net.InetAddress;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthDtos.TokenView;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.AuthService.SessionResult;
import com.szpu.algohub.auth.RefreshCookies;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;

/**
 * 示例环境的免验证码登录：截图脚本（frontend/scripts/shots.mjs）用示例账号的用户名直接登录。
 * 普通用户只能用邮箱验证码或 GitHub 登录，密码只留给管理员；脚本每次都去日志里找验证码太绕，
 * 同一个邮箱每天也最多收 10 封验证码，所以示例环境单独开这个入口。
 * 只在 demo 配置下存在（生产不能同时开 demo，见 ProductionGuard），而且只接受本机发来的请求。
 */
@RestController
@Profile("demo & !prod")
public class DemoLoginController {

    public record DemoLoginRequest(@NotBlank String username) {
    }

    private final AppUserMapper users;
    private final AuthService authService;
    private final RefreshCookies cookies;

    public DemoLoginController(AppUserMapper users, AuthService authService, RefreshCookies cookies) {
        this.users = users;
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/api/auth/demo-login")
    public ResponseEntity<ApiResult<TokenView>> login(@Valid @RequestBody DemoLoginRequest request,
                                                      HttpServletRequest http) {
        if (!loopback(http.getRemoteAddr())) {
            throw ApiException.forbidden("示例登录只接受本机请求");
        }
        AppUser user = users.findByUsername(request.username().strip().toLowerCase(Locale.ROOT));
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw ApiException.notFound("没有这个示例账号");
        }
        SessionResult session = authService.issueSession(user, http.getRemoteAddr(), http.getHeader("User-Agent"),
                "LOGIN_SUCCESS");
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookies.create(session.refreshToken(), authService.refreshTokenTtl()).toString())
                .body(ApiResult.ok(session.view()));
    }

    private static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
