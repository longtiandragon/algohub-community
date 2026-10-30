package com.szpu.algohub.admin.console;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.LoginUser;
import com.szpu.algohub.common.ApiResult;

/** 前端每次切换页面上报一次（未登录也可以），见 VisitService。 */
@RestController
public class VisitController {

    /** route：前端路由名，如 ranking */
    public record VisitRequest(String route) {
    }

    private final VisitService visits;

    public VisitController(VisitService visits) {
        this.visits = visits;
    }

    @PostMapping("/api/visits")
    public ApiResult<Void> visit(@RequestBody(required = false) VisitRequest request, HttpServletRequest http) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        LoginUser user = auth != null && auth.getPrincipal() instanceof LoginUser u ? u : null;
        visits.record(request == null ? null : request.route(), user, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ApiResult.ok();
    }
}
