package com.szpu.algohub.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/**
 * 邮箱验证码的按收件地址防护，登录码、找回密码码、绑定/更换登录邮箱的码合并计数：
 * <ul>
 *   <li>24 小时窗口内累计猜错 {@value #MAX_FAILURES} 次锁定 15 分钟，锁定期内发码和验码都拒绝；
 *       重发验证码不会清零（否则「发码→猜几次→重发」可以无限循环）；</li>
 *   <li>每个收件地址每天最多发 {@value #DAILY_SENDS} 封验证码邮件（IP 与全站配额在 AuthRateLimitConfig）。</li>
 * </ul>
 * Redis key 里只放邮箱摘要，不放原文。
 */
@Component
public class EmailCodeGuard {

    public static final int MAX_FAILURES = 10;
    public static final int DAILY_SENDS = 10;
    static final Duration WINDOW = Duration.ofHours(24);
    // 公共验码接口仍可被用于针对邮箱的拒绝服务；短时锁定只缩短影响，不代表消除了该风险。
    static final Duration LOCK = Duration.ofMinutes(15);
    private static final String FAIL_PREFIX = "algohub:email-code:fail:";
    private static final String LOCK_PREFIX = "algohub:email-code:lock:";
    static final String LOCKED_MESSAGE = "该邮箱验证码输错次数过多，已锁定 15 分钟，请稍后再试或改用其他方式登录";

    /** 计数 +1；首次计数设窗口；达到上限时写入锁并清掉计数。返回本次之后的次数。 */
    private static final DefaultRedisScript<Long> FAIL = new DefaultRedisScript<>("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        if n >= tonumber(ARGV[3]) then
          redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
          redis.call('DEL', KEYS[1])
        end
        return n
        """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthRateLimit limiter;

    public EmailCodeGuard(StringRedisTemplate redis, AuthRateLimit limiter) {
        this.redis = redis;
        this.limiter = limiter;
    }

    private static String key(String email) {
        return EmailLoginService.hash(EmailIdentityService.normalize(email));
    }

    public boolean locked(String email) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + key(email)));
    }

    public void requireNotLocked(String email) {
        if (locked(email)) {
            throw new ApiException(ErrorKind.RATE_LIMITED, LOCKED_MESSAGE);
        }
    }

    /** 记一次猜错。返回 true 表示这一次触发了锁定（调用方应作废该邮箱的待验证码）。 */
    public boolean recordFailure(String email) {
        String k = key(email);
        Long n = redis.execute(FAIL, List.of(FAIL_PREFIX + k, LOCK_PREFIX + k),
                Long.toString(WINDOW.toSeconds()), Long.toString(LOCK.toSeconds()), Integer.toString(MAX_FAILURES));
        return n != null && n >= MAX_FAILURES;
    }

    /** 发信前调用：计入该收件地址当天的发送次数，超过上限拒绝。 */
    public void countSend(String email) {
        limiter.check("mail:address", key(email), DAILY_SENDS, (int) Duration.ofDays(1).toSeconds(),
                "该邮箱今天接收验证码的次数已达上限（每天 " + DAILY_SENDS + " 封），请明天再试");
    }
}
