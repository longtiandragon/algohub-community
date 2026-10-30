package com.szpu.algohub.auth;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;

/**
 * GitHub 授权绑定到发起它的浏览器：发起时给浏览器种一个 HttpOnly Cookie（随机串，不出现在授权链接里），
 * 回调时 Cookie、发起人、身份版本都对上才继续。别人把自己的回调链接塞给你打开，你的浏览器里没有这个 Cookie，会被拦下。
 */
@Service
public class OauthBrowserFlow {

    public static final String COOKIE = "algohub_oauth_browser";
    private static final String KEY_PREFIX = "algohub:oauth:browser:";
    private static final Duration TTL = Duration.ofMinutes(10);

    /** mode：LOGIN 登录、BIND 绑定、REAUTH 敏感操作前确认本人（purpose 是操作用途） */
    public record Flow(Long userId, String version, String browserHash, String mode, String purpose) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AppUserMapper users;

    public OauthBrowserFlow(StringRedisTemplate redis, ObjectMapper json, AppUserMapper users) {
        this.redis = redis;
        this.json = json;
        this.users = users;
    }

    /** 记下这次授权，返回要写进 Cookie 的随机串 */
    public String create(String state, Long userId, String mode, String purpose) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        String version = userId == null ? "" : IdentitySecurity.version(users.findById(userId));
        try {
            redis.opsForValue().set(KEY_PREFIX + state,
                    json.writeValueAsString(new Flow(userId, version, EmailLoginService.hash(secret), mode, purpose)), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("无法记录 GitHub 授权", e);
        }
        return secret;
    }

    /** 回调时核对并作废（只能用一次）。调用方要在事务里：已登录的发起人会锁住用户行 */
    public Flow consume(String state, String secret, Long currentUserId) {
        if (state == null || state.length() > 100 || secret == null) {
            throw ApiException.forbidden("请在发起授权的浏览器里重新授权");
        }
        String key = KEY_PREFIX + state;
        String raw = redis.opsForValue().get(key);
        Flow flow;
        try {
            flow = json.readValue(raw, Flow.class);
        } catch (Exception e) {
            throw ApiException.forbidden("授权已失效，请重新发起");
        }
        if (!flow.browserHash().equals(EmailLoginService.hash(secret))) {
            throw ApiException.forbidden("请在发起授权的浏览器里重新授权");
        }
        if (!Objects.equals(flow.userId(), currentUserId)) {
            throw ApiException.forbidden("登录身份已改变，请重新发起授权");
        }
        if (flow.userId() != null) {
            AppUser user = users.lockIdentity(flow.userId());
            if (user == null || !"ACTIVE".equals(user.getStatus()) || !flow.version().equals(IdentitySecurity.version(user))) {
                throw ApiException.forbidden("身份已变化，请重新登录");
            }
        }
        if (!Boolean.TRUE.equals(redis.delete(key))) {
            throw ApiException.forbidden("这次授权已经用过了，请重新发起");
        }
        return flow;
    }
}
