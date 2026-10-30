package com.szpu.algohub.auth;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import com.szpu.algohub.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** One-time host migration. Credentials travel only in a short-lived HttpOnly cookie, never URLs. */
@RestController
@RequestMapping("/api/auth/domain-migration")
public class DomainMigrationController {
    static final String BRIDGE = "algohub_domain_bridge";
    static final String FINISH = "/api/auth/domain-migration/finish";
    private final AuthService auth;
    private final RefreshCookies cookies;
    private final String origin;
    public DomainMigrationController(AuthService auth, RefreshCookies cookies, @Value("${PUBLIC_ORIGIN:}") String origin) {
        this.auth=auth; this.cookies=cookies; this.origin=origin;
    }
    private void requireHost(HttpServletRequest request, String host) {
        if (!"https://algohub.top".equals(origin) || !host.equals(request.getServerName()))
            throw ApiException.forbidden("此入口仅用于正式站域名迁移");
    }
    static String destination(String path) {
        if (path==null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.contains("\r") || path.contains("\n") || path.startsWith("/api/")) return "/";
        try { URI u=URI.create(path); if(u.getRawAuthority()!=null || u.isAbsolute()) return "/"; }
        catch (IllegalArgumentException e) {return "/";}
        return path;
    }
    private static String read(HttpServletRequest request, String name) {
        if(request.getCookies()!=null) for(var c:request.getCookies()) if(name.equals(c.getName())) return c.getValue();
        return null;
    }
    private ResponseCookie bridge(String value, Duration ttl) {
        return ResponseCookie.from(BRIDGE,value).domain("algohub.top").path(FINISH)
                .httpOnly(true).secure(true).sameSite("Strict").maxAge(ttl).build();
    }
    private ResponseEntity.BodyBuilder redirect(String path) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION,"https://algohub.top"+destination(path))
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("Referrer-Policy","no-referrer");
    }
    @GetMapping("/start")
    public ResponseEntity<Void> start(HttpServletRequest request, @RequestParam(defaultValue="/") String returnTo) {
        requireHost(request,"www.algohub.top");
        String old=read(request,AuthController.REFRESH_COOKIE);
        if(old==null || !old.matches("[A-Za-z0-9_-]{64}")) return redirect(returnTo).build();
        // Do not consume the source session yet: an interrupted redirect can safely be retried.
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION,"https://algohub.top"+FINISH+"?returnTo="+
                java.net.URLEncoder.encode(destination(returnTo),java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.SET_COOKIE,bridge(old,Duration.ofSeconds(60)).toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("Referrer-Policy","no-referrer").build();
    }
    @GetMapping("/finish")
    public ResponseEntity<Void> finish(HttpServletRequest request, @RequestParam(defaultValue="/") String returnTo) {
        requireHost(request,"algohub.top");
        var response=redirect(returnTo).header(HttpHeaders.SET_COOKIE,bridge("",Duration.ZERO).toString());
        // An existing target login always wins; migration must never switch an already logged-in identity.
        if(read(request,AuthController.REFRESH_COOKIE)!=null) return response.build();
        String old=read(request,BRIDGE);
        if(old==null) return response.build();
        try {
            var session=auth.refresh(old,request.getRemoteAddr(),request.getHeader("User-Agent"));
            return response.header(HttpHeaders.SET_COOKIE,cookies.create(session.refreshToken(),auth.refreshTokenTtl()).toString()).build();
        } catch(com.szpu.algohub.common.ApiException e) {
            if(e.getKind()!=com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED) throw e;
            return response.build();
        }
    }
}
