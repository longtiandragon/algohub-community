package com.szpu.algohub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/** 固定窗口原子限流。先限制单 IP，再计入总量；原始 IP/邮箱不写进 Redis key。 */
@Component
public class AuthRateLimit {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return n
        """, Long.class);
    public AuthRateLimit(StringRedisTemplate redis) { this.redis = redis; }
    public void check(String bucket, String identity, int limit, int seconds) {
        check(bucket, identity, limit, seconds, "请求过于频繁，请稍后重试");
    }
    public void check(String bucket, String identity, int limit, int seconds, String message) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Long n = redis.execute(COUNT, List.of("algohub:rate:" + bucket + ":" + digest), Integer.toString(seconds));
        if (n == null || n > limit) throw new ApiException(ErrorKind.RATE_LIMITED, message);
    }
}
