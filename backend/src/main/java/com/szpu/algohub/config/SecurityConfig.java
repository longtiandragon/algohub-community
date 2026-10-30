package com.szpu.algohub.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.szpu.algohub.auth.JwtAuthFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 无状态鉴权：访问令牌走 Authorization 头，服务端不保存会话，因此不需要 CSRF Token。
 * 刷新令牌放在 SameSite=Strict 的 HttpOnly Cookie 里，且只在 /api/auth 路径下发送。
 *
 * 除注册登录等少数入口外，其余接口一律要求登录：学习记录、排行榜与个人档案都属于身份相关数据。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 明确指定强度，避免默认值随版本变化
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/auth/enrollment-years", "/api/auth/password-reset", "/api/auth/password-reset/code", "/api/auth/email/status", "/api/auth/email/code", "/api/auth/email/login",
                                "/api/auth/login", "/api/auth/demo-login", "/api/auth/register", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/me",
                                "/api/auth/github/status", "/api/auth/github/authorize-url",
                                "/api/auth/github/callback", "/api/auth/github/link-login", "/api/auth/github/link-context", "/api/auth/github/link-code").permitAll()
                        .requestMatchers("/api/auth/domain-migration/start", "/api/auth/domain-migration/finish").permitAll()
                        .requestMatchers("/api/luogu/browser-import/page", "/api/luogu/browser-import/auto-begin").permitAll()
                        .requestMatchers("/api/leetcode/browser-import/page", "/api/leetcode/browser-import/auto-begin").permitAll()
                        .requestMatchers("/api/qoj/browser-import/page", "/api/qoj/browser-import/auto-begin").permitAll()
                        // 访问统计：未登录也上报（带令牌时 JwtAuthFilter 照常解析，按登录用户记）
                        .requestMatchers(HttpMethod.POST, "/api/visits").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeJson(res,
                                HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((req, res, e) -> writeJson(res,
                                HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有访问该资源的权限")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(opt -> {
                        })
                        .referrerPolicy(policy -> policy.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.SAME_ORIGIN)))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());

        return http.build();
    }

    private static void writeJson(HttpServletResponse response, int status, String kind, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"data\":null,\"errorKind\":\"" + kind + "\",\"message\":\"" + message + "\"}");
    }
}
