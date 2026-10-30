package com.szpu.algohub.account;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.szpu.algohub.auth.AuthRateLimit;

/**
 * 会让后端请求外站的用户入口（查询/验证平台账号、添加个人关注、保存或试用登录态、牛客 Tracker 实时读取、
 * 码蹄集实时翻页……）统一限频：按本站用户，再按来源 IP。避免一个人（或一个 IP 上批量注册的小号）
 * 反复点击把源站限速额度用光，连累其他同学的同步任务。
 *
 * IP 取自当前请求的 remoteAddr（生产只信任本机 Nginx 转发的地址）；不在请求线程里调用时只按用户计数。
 * 同一校园出口 IP 后面有很多同学，所以 IP 上限比单人宽得多。
 */
@Component
public class IdentityLookupGuard {

    static final int LIMIT = 30;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final AuthRateLimit limiter;
    private final int perUser;
    private final int perIp;
    private final int windowSeconds;

    public IdentityLookupGuard(AuthRateLimit limiter,
                               @Value("${algohub.outbound-guard.per-user:" + LIMIT + "}") int perUser,
                               @Value("${algohub.outbound-guard.per-ip:300}") int perIp,
                               @Value("${algohub.outbound-guard.window-minutes:10}") int windowMinutes) {
        if (perUser < 1 || perIp < 1 || windowMinutes < 1) {
            throw new IllegalArgumentException("algohub.outbound-guard 各项必须大于 0");
        }
        this.limiter = limiter;
        this.perUser = perUser;
        this.perIp = perIp;
        this.windowSeconds = (int) Duration.ofMinutes(windowMinutes).toSeconds();
    }

    public void acquire(Long userId) {
        acquire(userId, 1);
    }

    /** cost：一次操作大约会发出的外站请求数（例如牛客 Tracker 一页是 1 + N 个请求）。 */
    public void acquire(Long userId, int cost) {
        String message = "请求外站过于频繁，请 " + windowSeconds / 60 + " 分钟后再试";
        String ip = currentIp();
        for (int i = 0; i < cost; i++) {
            limiter.check("outbound:user", String.valueOf(userId), perUser, windowSeconds, message);
            if (ip != null) {
                limiter.check("outbound:ip", ip, perIp, windowSeconds, "当前网络请求外站过于频繁，请稍后再试");
            }
        }
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
