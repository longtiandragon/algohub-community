package com.szpu.algohub.admin.console;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.szpu.algohub.auth.LoginUser;
import com.szpu.algohub.common.ApiException;

/**
 * 管理接口的两道拦截（Spring Security 已经保证是管理员）：
 * 1. 按路径检查权限：AdminPermission 里没配的管理接口一律拒绝；
 * 2. 非 GET 请求处理完后记操作日志（成功失败都记）。
 * 日志拦截器先登记：Spring 只给 preHandle 已放行的拦截器回调 afterCompletion，这样被权限拒绝的请求也会记下来。
 */
@Configuration
public class AdminConsoleConfig implements WebMvcConfigurer {

    private static final Set<String> READ_ONLY = Set.of("GET", "HEAD", "OPTIONS");

    private final AdminAccess access;
    private final AdminAuditLog audit;

    public AdminConsoleConfig(AdminAccess access, AdminAuditLog audit) {
        this.access = access;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                        Exception ex) {
                LoginUser actor = currentUser();
                if (actor == null || READ_ONLY.contains(request.getMethod())) {
                    return;
                }
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                int status = ex != null && response.getStatus() < 400 ? 500 : response.getStatus();
                audit.record(actor, request.getMethod(), pattern == null ? path(request) : pattern.toString(),
                        target(request), status, request.getRemoteAddr());
            }
        }).addPathPatterns("/api/admin/**", "/api/school-admin/**");

        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = path(request);
                if (path.equals(AdminPermission.ME_PATH)) {
                    return true;
                }
                AdminPermission needed = AdminPermission.forPath(path)
                        .orElseThrow(() -> ApiException.forbidden("这个管理接口还没有配置权限"));
                LoginUser user = currentUser();
                if (user == null || !access.of(user.getId()).has(needed)) {
                    throw ApiException.forbidden("你没有「" + needed.label() + "」权限");
                }
                return true;
            }
        }).addPathPatterns("/api/admin/**");
    }

    private static LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser user ? user : null;
    }

    /** DispatcherServlet 已规范化的路径；MockMvc 里 servletPath 为空，用 requestURI */
    private static String path(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || path.isEmpty() ? request.getRequestURI() : path;
    }

    /** 路径变量，如 id=12 */
    @SuppressWarnings("unchecked")
    private static String target(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        return ((Map<String, String>) map).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
