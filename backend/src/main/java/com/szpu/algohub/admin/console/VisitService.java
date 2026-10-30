package com.szpu.algohub.admin.console;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.szpu.algohub.auth.AuthRateLimit;
import com.szpu.algohub.auth.LoginUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;

/**
 * 访问统计：前端每次切换页面上报一次路由名，按「访客 × 天」累计打开次数，另按「路由 × 天」累计页面打开次数。
 * 登录用户的访客键是 u:<id>；未登录的是当天 IP + User-Agent 的哈希，不设 Cookie，第二天就对不上了，不做长期跟踪。
 * 管理员不计入（自己看后台不算访问量）。上报失败、超频都静默忽略，不打扰页面。
 */
@Service
public class VisitService {

    private static final Pattern ROUTE = Pattern.compile("^[a-z0-9-]{1,64}$");
    /** 同一 IP 每分钟最多记这么多次 */
    static final int PER_IP_PER_MINUTE = 120;

    private final JdbcTemplate jdbc;
    private final AuthRateLimit limiter;

    public VisitService(JdbcTemplate jdbc, AuthRateLimit limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    /** @return 这次有没有记下来（测试用） */
    public boolean record(String route, LoginUser user, String ip, String userAgent) {
        if (route == null || !ROUTE.matcher(route).matches()) {
            return false;
        }
        if (user != null && "ADMIN".equals(user.getRole())) {
            return false;
        }
        try {
            limiter.check("visit:ip", ip == null ? "" : ip, PER_IP_PER_MINUTE, 60);
        } catch (ApiException e) {
            return false;
        }
        LocalDate day = TimeSupport.today();
        Long userId = user == null ? null : user.getId();
        String key = userId != null ? "u:" + userId
                : "a:" + sha256(day + "|" + (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent)).substring(0, 32);
        jdbc.update("""
                insert into site_visit_daily (day, visitor_key, user_id) values (?, ?, ?)
                on conflict (day, visitor_key) do update set views = site_visit_daily.views + 1, last_at = now()
                """, day, key, userId);
        jdbc.update("""
                insert into site_page_daily (day, route) values (?, ?)
                on conflict (day, route) do update set views = site_page_daily.views + 1
                """, day, route);
        if (userId != null) {
            // 最近活跃时间：隔 5 分钟以上才写，免得每次切页面都改用户表
            jdbc.update("""
                    update app_user set last_seen_at = now()
                     where id = ? and (last_seen_at is null or last_seen_at < now() - interval '5 minutes')
                    """, userId);
        }
        return true;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
