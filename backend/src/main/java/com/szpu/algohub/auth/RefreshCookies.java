package com.szpu.algohub.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import com.szpu.algohub.config.AppProperties;

/** 所有签发和清除入口共用相同的 Cookie 属性。 */
@Component
public class RefreshCookies {
    private final AppProperties properties;
    public RefreshCookies(AppProperties properties) { this.properties = properties; }
    public ResponseCookie create(String value, Duration ttl) {
        return ResponseCookie.from(AuthController.REFRESH_COOKIE, value)
                .httpOnly(true).secure(properties.getSecurity().isSecureCookie())
                .sameSite("Strict").path("/api/auth").maxAge(ttl).build();
    }
    public ResponseCookie clear() { return create("", Duration.ZERO); }
}
