package com.szpu.algohub.auth;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.szpu.algohub.common.ApiException;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 从 Authorization: Bearer 读取访问令牌并建立安全上下文。
 * 除签名与有效期外，还检查两件事：令牌是否被登出拉黑、签发时间是否早于用户的 tokens_valid_from。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final AppUserMapper userMapper;

    public JwtAuthFilter(JwtService jwtService, TokenStore tokenStore, AppUserMapper userMapper) {
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7).trim();
        try {
            Claims claims = jwtService.parse(token);
            if (tokenStore.isBlacklisted(claims.getId())) {
                throw new ApiException(com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED, "该登录状态已登出");
            }
            AppUser user = userMapper.findById(Long.valueOf(claims.getSubject()));
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                throw new ApiException(com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED, "账号不可用");
            }
            Instant issuedAt = claims.getIssuedAt().toInstant();
            String version=user.getTokensValidFrom()==null ? "" : user.getTokensValidFrom().toString();
            if(!java.util.Objects.equals(version,claims.get("identityVersion",String.class)))
                throw new ApiException(com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED,"登录状态已失效，请重新登录");
            // JWT 的 iat 只精确到秒，而 tokens_valid_from 带毫秒；比较前把后者截断到秒，
            // 否则同一秒内签发的新令牌会被误判为“改密之前签发”。
            Instant validFrom = user.getTokensValidFrom() == null
                    ? null
                    : user.getTokensValidFrom().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            if (validFrom != null && issuedAt.isBefore(validFrom)) {
                // 改密或“退出所有设备”之后签发的旧令牌一律失效
                throw new ApiException(com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED,
                        "登录状态已失效，请重新登录");
            }
            LoginUser principal = new LoginUser(user, claims.getId(), issuedAt,
                    claims.getExpiration().toInstant());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"success\":false,\"data\":null,\"errorKind\":\""
                    + e.getKind().name() + "\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
