package com.szpu.algohub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.account.CredentialCipher;

/**
 * 会话状态存放在 Redis：刷新令牌、访问令牌黑名单、登录失败计数、OAuth state。
 *
 * 刷新令牌本身是随机串，会话索引只存 SHA-256 摘要，每次刷新轮换。
 * 为恢复网络中断时丢失的 Cookie，另存 30 秒 AES-GCM 加密的后继；明文不进入 Redis 或日志。
 */
@Component
public class TokenStore {

    private static final String REFRESH_PREFIX = "algohub:refresh:";
    private static final String RETRY_PREFIX = "algohub:refresh-retry:";
    private static final String USER_RETRIES_PREFIX = "algohub:refresh-retries:";
    static final long RETRY_SECONDS = 30;
    private static final String USER_SESSIONS_PREFIX = "algohub:sessions:";
    private static final String BLACKLIST_PREFIX = "algohub:jwt:blacklist:";
    private static final String LOGIN_FAIL_PREFIX = "algohub:login:fail:";
    private static final String OAUTH_STATE_PREFIX = "algohub:oauth:state:";

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final CredentialCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public TokenStore(StringRedisTemplate redis, AppProperties properties, CredentialCipher cipher) {
        this.redis = redis;
        this.properties = properties;
        this.cipher = cipher;
    }

    public record RefreshToken(String value, String hash, Instant expiresAt) {
    }

    public record RefreshIdentity(Long userId, String identityVersion) {
    }

    public record SessionInfo(String hash, Instant createdAt, String device, boolean current) {
    }

    private Duration refreshTtl() {
        return Duration.ofDays(properties.getSecurity().getRefreshTokenDays());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiException(ErrorKind.INTERNAL, "令牌摘要计算失败");
        }
    }

    /** 签发刷新令牌，并登记到该用户的会话集合；超出上限时淘汰最早的会话。 */
    public RefreshToken issueRefreshToken(Long userId, String device) {
        return issueRefreshToken(userId, device, "");
    }

    /** identityVersion：签发时的身份版本（见 IdentitySecurity），刷新时对不上就作废 */
    public RefreshToken issueRefreshToken(Long userId, String device, String identityVersion) {
        byte[] raw = new byte[48];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = hash(value);
        Instant expiresAt = Instant.now().plus(refreshTtl());

        String sessionsKey = USER_SESSIONS_PREFIX + userId;
        // 写令牌、登记会话、超出上限淘汰最早的会话，一次 Redis 操作做完
        redis.execute(new DefaultRedisScript<>("""
                redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'createdAt', ARGV[2], 'device', ARGV[3], 'identityVersion', ARGV[7])
                redis.call('EXPIRE', KEYS[1], ARGV[4])
                redis.call('ZADD', KEYS[2], ARGV[5], ARGV[6])
                redis.call('EXPIRE', KEYS[2], ARGV[4])
                local extra = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[8])
                if extra > 0 then
                    local oldest = redis.call('ZRANGE', KEYS[2], 0, extra - 1)
                    for _, h in ipairs(oldest) do redis.call('DEL', ARGV[9] .. h); redis.call('ZREM', KEYS[2], h) end
                end
                return 1
                """, Long.class),
                List.of(REFRESH_PREFIX + tokenHash, sessionsKey),
                userId.toString(), Instant.now().toString(), device == null ? "" : device,
                Long.toString(refreshTtl().toSeconds()), Long.toString(Instant.now().toEpochMilli()), tokenHash,
                identityVersion, Integer.toString(properties.getSecurity().getMaxSessionsPerUser()), REFRESH_PREFIX);
        return new RefreshToken(value, tokenHash, expiresAt);
    }

    /**
     * 已消费的令牌只能在短重试窗口内恢复同一个后继，且后继仍有效、身份和浏览器相同。
     * 网络中断或刷新页面可能丢弃 Set-Cookie；不能把这当成用户主动退出。
     */
    public RefreshIdentity refreshIdentity(String presentedToken, String device) {
        String tokenHash = hash(presentedToken);
        List<?> values = redis.execute(new DefaultRedisScript<>("""
            local user=redis.call('HGET',KEYS[1],'userId')
            if user then return {user,redis.call('HGET',KEYS[1],'identityVersion') or ''} end
            local retry=KEYS[2]
            local version=nil
            for i=1,256 do
                local owner=redis.call('HGET',retry,'userId')
                local v=redis.call('HGET',retry,'identityVersion') or ''
                if not owner or redis.call('HGET',retry,'device') ~= ARGV[1]
                    or (user and (user ~= owner or version ~= v)) then return nil end
                user=owner;version=v
                local next=redis.call('HGET',retry,'nextHash')
                if not next then return nil end
                local active=redis.call('HGET',ARGV[2]..next,'userId')
                if active then
                    if active ~= user or (redis.call('HGET',ARGV[2]..next,'identityVersion') or '') ~= version then return nil end
                    return {user,version}
                end
                retry=ARGV[3]..next
            end
            return nil
            """, List.class), List.of(REFRESH_PREFIX + tokenHash, RETRY_PREFIX + tokenHash),
                device == null ? "" : device, REFRESH_PREFIX, RETRY_PREFIX);
        return values == null || values.size() != 2 ? null
                : new RefreshIdentity(Long.valueOf(values.get(0).toString()), values.get(1).toString());
    }

    /** 校验并轮换；丢失响应的 30 秒内重试返回同一个后继，不新增会话或延长重试窗口。 */
    public RefreshToken rotate(String presentedToken, String device) {
        String tokenHash = hash(presentedToken);
        RefreshIdentity identity = refreshIdentity(presentedToken, device);
        if (identity == null) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "刷新令牌无效或已过期，请重新登录");
        }
        Long userId = identity.userId();
        byte[] raw = new byte[48];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String nextHash = hash(value);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(refreshTtl());
        // Redis 只保留令牌摘要；短期重试需要恢复后继值，使用站点现有的 AES-GCM 密钥加密。
        String encrypted = cipher.encrypt(value);
        List<?> result = redis.execute(new DefaultRedisScript<>("""
            if redis.call('HGET',KEYS[1],'userId') ~= ARGV[1] then
                local retry=KEYS[4]
                for i=1,256 do
                    if redis.call('HGET',retry,'userId') ~= ARGV[1]
                        or redis.call('HGET',retry,'device') ~= ARGV[5]
                        or (redis.call('HGET',retry,'identityVersion') or '') ~= ARGV[8] then return nil end
                    local next=redis.call('HGET',retry,'nextHash')
                    if not next then return nil end
                    local active=redis.call('HGET',ARGV[12]..next,'userId')
                    if active then
                        if active ~= ARGV[1] or (redis.call('HGET',ARGV[12]..next,'identityVersion') or '') ~= ARGV[8] then return nil end
                        return {redis.call('HGET',retry,'encrypted'),next,redis.call('HGET',retry,'expiresAt')}
                    end
                    retry=ARGV[13]..next
                end
                return nil
            end
            local version=redis.call('HGET',KEYS[1],'identityVersion') or ''
            if version ~= ARGV[8] then return nil end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[3], ARGV[2])
            redis.call('HSET', KEYS[2], 'userId', ARGV[1], 'createdAt', ARGV[4], 'device', ARGV[5], 'identityVersion', version)
            redis.call('EXPIRE', KEYS[2], ARGV[6])
            redis.call('ZADD', KEYS[3], ARGV[7], ARGV[3])
            redis.call('EXPIRE', KEYS[3], ARGV[6])
            redis.call('HSET',KEYS[4],'userId',ARGV[1],'device',ARGV[5],'identityVersion',version,
                'nextHash',ARGV[3],'encrypted',ARGV[9],'expiresAt',ARGV[10])
            redis.call('EXPIRE',KEYS[4],ARGV[11])
            redis.call('ZREMRANGEBYSCORE',KEYS[5],'-inf',ARGV[7])
            redis.call('ZADD',KEYS[5],tonumber(ARGV[7])+tonumber(ARGV[11])*1000,ARGV[2])
            redis.call('EXPIRE',KEYS[5],ARGV[11])
            return {ARGV[9],ARGV[3],ARGV[10]}
            """, List.class), List.of(REFRESH_PREFIX + tokenHash, REFRESH_PREFIX + nextHash,
                    USER_SESSIONS_PREFIX + userId, RETRY_PREFIX + tokenHash, USER_RETRIES_PREFIX + userId), userId.toString(), tokenHash, nextHash,
                    now.toString(), device == null ? "" : device,
                    Long.toString(refreshTtl().toSeconds()), Long.toString(now.toEpochMilli()),
                    identity.identityVersion(), encrypted, expiresAt.toString(), Long.toString(RETRY_SECONDS), REFRESH_PREFIX, RETRY_PREFIX);
        if (result == null || result.size() != 3) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "刷新令牌无效或已使用，请重新登录");
        }
        String successor = cipher.decrypt(result.get(0).toString());
        String successorHash = result.get(1).toString();
        if (!hash(successor).equals(successorHash)) {
            throw new ApiException(ErrorKind.INTERNAL, "无法恢复登录会话，请稍后重试");
        }
        return new RefreshToken(successor, successorHash, Instant.parse(result.get(2).toString()));
    }

    /** 刷新令牌签发时的身份版本和现在一致（这个功能上线前签发的令牌没有版本，按不一致处理，要重新登录一次） */
    public boolean matchesIdentity(String presentedToken, String version) {
        Object stored = redis.opsForHash().get(REFRESH_PREFIX + hash(presentedToken), "identityVersion");
        return stored != null && version.equals(stored.toString());
    }

    public Long userIdOfRefreshToken(String presentedToken) {
        Object userId = redis.opsForHash().get(REFRESH_PREFIX + hash(presentedToken), "userId");
        return userId == null ? null : Long.valueOf(userId.toString());
    }

    public void revokeRefreshToken(String presentedToken) {
        String tokenHash = hash(presentedToken);
        // 退出请求也可能携带刚消费的旧 Cookie；沿短期后继链一起撤销，不能留下仍可恢复的会话。
        redis.execute(new DefaultRedisScript<>("""
            local h=ARGV[1]
            local owner=nil
            for i=1,256 do
                local active=ARGV[2]..h
                local retry=ARGV[3]..h
                local user=redis.call('HGET',active,'userId') or redis.call('HGET',retry,'userId')
                if user then owner=user end
                local next=redis.call('HGET',retry,'nextHash')
                redis.call('DEL',active,retry)
                if user then redis.call('ZREM',ARGV[4]..user,h);redis.call('ZREM',ARGV[5]..user,h) end
                if not next then return 1 end
                h=next
            end
            -- 极端高频轮换超过遍历上限时，宁可撤销该用户所有会话，也不能声称退出但留下后继。
            if owner then
                local sessions=ARGV[4]..owner
                local retries=ARGV[5]..owner
                for _, token in ipairs(redis.call('ZRANGE',sessions,0,-1)) do redis.call('DEL',ARGV[2]..token) end
                for _, token in ipairs(redis.call('ZRANGE',retries,0,-1)) do redis.call('DEL',ARGV[3]..token) end
                redis.call('DEL',sessions,retries)
            end
            return 1
            """, Long.class), List.of(), tokenHash, REFRESH_PREFIX, RETRY_PREFIX,
                USER_SESSIONS_PREFIX, USER_RETRIES_PREFIX);
    }

    /** 退出所有设备：清掉该用户的全部刷新令牌。 */
    public int revokeAllSessions(Long userId) {
        String sessionsKey = USER_SESSIONS_PREFIX + userId;
        Long count = redis.execute(new DefaultRedisScript<>("""
            local hashes = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, hash in ipairs(hashes) do redis.call('DEL', ARGV[1] .. hash) end
            local retries=redis.call('ZRANGE',KEYS[2],0,-1)
            for _, hash in ipairs(retries) do redis.call('DEL',ARGV[2]..hash) end
            redis.call('DEL', KEYS[1],KEYS[2])
            return #hashes
            """, Long.class), List.of(sessionsKey, USER_RETRIES_PREFIX + userId), REFRESH_PREFIX, RETRY_PREFIX);
        return count == null ? 0 : count.intValue();
    }

    public List<SessionInfo> listSessions(Long userId, String currentRefreshToken) {
        String currentHash = currentRefreshToken == null ? null : hash(currentRefreshToken);
        Set<String> hashes = redis.opsForZSet().reverseRange(USER_SESSIONS_PREFIX + userId, 0, -1);
        List<SessionInfo> sessions = new ArrayList<>();
        if (hashes == null) {
            return sessions;
        }
        for (String tokenHash : hashes) {
            Map<Object, Object> entries = redis.opsForHash().entries(REFRESH_PREFIX + tokenHash);
            if (entries.isEmpty()) {
                continue;
            }
            Object createdAt = entries.get("createdAt");
            sessions.add(new SessionInfo(
                    tokenHash.substring(0, Math.min(8, tokenHash.length())),
                    createdAt == null ? null : Instant.parse(createdAt.toString()),
                    String.valueOf(entries.getOrDefault("device", "")),
                    tokenHash.equals(currentHash)));
        }
        return sessions;
    }

    /** 登出时把访问令牌的 jti 拉黑，直到它自然过期。 */
    public void blacklistAccessToken(String jti, Instant expiresAt) {
        long seconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
        redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(seconds));
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }

    /** 登录失败计数，防止对同一账号持续撞库。返回当前失败次数。 */
    public long recordLoginFailure(String username) {
        String key = LOGIN_FAIL_PREFIX + username.toLowerCase();
        Long count = redis.execute(new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class), List.of(key), Long.toString(Duration.ofMinutes(properties.getSecurity().getLoginLockMinutes()).toSeconds()));
        return count == null ? 0 : count;
    }

    public long loginFailures(String username) {
        String value = redis.opsForValue().get(LOGIN_FAIL_PREFIX + username.toLowerCase());
        return value == null ? 0 : Long.parseLong(value);
    }

    public void clearLoginFailures(String username) {
        redis.delete(LOGIN_FAIL_PREFIX + username.toLowerCase());
    }

    /** OAuth 授权用的一次性 state，防止 CSRF 与回调重放。 */
    public String createOauthState(Long userId, String provider) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(OAUTH_STATE_PREFIX + provider + ":" + state,
                String.valueOf(userId), Duration.ofMinutes(10));
        return state;
    }

    public Long consumeOauthState(String state, String provider) {
        String key = OAUTH_STATE_PREFIX + provider + ":" + state;
        String userId = redis.opsForValue().getAndDelete(key);
        return userId == null ? null : Long.valueOf(userId);
    }

    public Duration refreshTokenTtl() {
        return refreshTtl();
    }
}
