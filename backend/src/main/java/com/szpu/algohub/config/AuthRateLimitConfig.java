package com.szpu.algohub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import com.szpu.algohub.auth.AuthRateLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** DispatcherServlet 已规范化路径；在反序列化和 BCrypt 之前拒绝过量请求。 */
@Configuration
public class AuthRateLimitConfig implements WebMvcConfigurer {
    private final AuthRateLimit limiter;
    private final int scale;
    public AuthRateLimitConfig(AuthRateLimit limiter, @Value("${algohub.security.rate-limit-scale:1}") int scale) {
        this.limiter = limiter;
        if (scale < 1) throw new IllegalArgumentException("rate-limit-scale 必须大于 0");
        this.scale = scale;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                String path = req.getServletPath();
                if (path.isEmpty()) path = req.getRequestURI(); // MockMvc
                boolean mail = path.equals("/api/auth/email/code") || path.equals("/api/auth/password-reset/code")
                        || path.equals("/api/me/login-email/code") || path.equals("/api/auth/security-action/code") || path.equals("/api/auth/github/link-code");
                boolean register = path.equals("/api/auth/register");
                boolean auth = path.startsWith("/api/auth/") && (!"GET".equals(req.getMethod())
                        || path.equals("/api/auth/github/authorize-url") || path.equals("/api/auth/github/link-context"));
                if (!mail && !register && !auth) return true;
                String bucket = mail ? "mail" : register ? "register" : "auth";
                limiter.check(bucket + ":ip", req.getRemoteAddr(), (mail ? 10 : register ? 10 : 120) * scale, 60);
                limiter.check(bucket + ":global", "all", (mail ? 100 : register ? 100 : 1200) * scale, 60);
                if (mail) limiter.check("mail:daily", "all", 2000 * scale, 86400);
                return true;
            }
        }).addPathPatterns("/api/auth/**", "/api/me/login-email/**");
    }
}
