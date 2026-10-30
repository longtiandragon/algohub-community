package com.szpu.algohub.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import com.szpu.algohub.mail.BrandedMail;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;

/**
 * 敏感操作前确认本人：注销账号、绑定/解绑 GitHub、第一次绑登录邮箱。
 * 用现有登录邮箱收验证码、输当前密码或重新授权已绑定的 GitHub，换一张 5 分钟有效、只能用一次的凭证；
 * 凭证限定本人、操作用途和身份版本，中途改了密码或登录方式就作废。只拿到登录态的人做不了这些操作。
 */
@Service
public class SecurityActionService {

    private static final Set<String> PURPOSES = Set.of("DELETE_ACCOUNT", "BIND_GITHUB", "UNBIND_GITHUB", "FIRST_EMAIL");
    private static final String CODE_PREFIX = "algohub:security:code:";
    private static final String PROOF_PREFIX = "algohub:security:proof:";
    private static final Duration TTL = Duration.ofMinutes(5);
    /** 同一张验证码最多试 5 次，第 6 次起连正确的也不认 */
    private static final DefaultRedisScript<Long> CHECK_CODE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local n = redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 300)
            if n > 5 then redis.call('DEL', KEYS[1]); return 0 end
            if value ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2]); return 1
            """, Long.class);

    private final AppUserMapper users;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PasswordEncoder encoder;
    private final ObjectProvider<JavaMailSender> sender;
    private final AuthRateLimit limits;
    private final ObjectMapper json;
    private final String from;
    private final SecureRandom random = new SecureRandom();

    public SecurityActionService(AppUserMapper users, JdbcTemplate jdbc, StringRedisTemplate redis, PasswordEncoder encoder,
                                 ObjectProvider<JavaMailSender> sender, AuthRateLimit limits, ObjectMapper json,
                                 @Value("${algohub.email.from:}") String from) {
        this.users = users;
        this.jdbc = jdbc;
        this.redis = redis;
        this.encoder = encoder;
        this.sender = sender;
        this.limits = limits;
        this.json = json;
        this.from = from;
    }

    public record Proof(long userId, String purpose, String version) {
    }

    public static void purpose(String purpose) {
        if (!PURPOSES.contains(purpose == null ? "" : purpose)) {
            throw ApiException.validation("无效的安全操作");
        }
    }

    private AppUser activeUser(long id) {
        AppUser user = users.findById(id);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("账号不可用");
        }
        return user;
    }

    /** 这个账号能用哪些方式确认本人 */
    public Map<String, Object> methods(long id) {
        AppUser user = activeUser(id);
        Integer github = jdbc.queryForObject(
                "select count(*) from user_oauth_binding where user_id = ? and provider = 'GITHUB'", Integer.class, id);
        return Map.of("password", AuthService.passwordEnabled(user),
                "email", users.verifiedEmailCount(id) > 0,
                "github", github != null && github > 0);
    }

    /** 往现有登录邮箱发验证码（不是要新绑的那个） */
    public void send(long id, String purpose) {
        purpose(purpose);
        AppUser user = activeUser(id);
        limits.check("security-mail:user", Long.toString(id), 1, 60);
        List<String> emails = jdbc.queryForList("select email from verified_login_email where user_id = ?", String.class, id);
        if (emails.isEmpty()) {
            throw ApiException.validation("请使用已绑定的 GitHub 确认身份；管理员也可使用密码");
        }
        if (sender.getIfAvailable() == null || from.isBlank()) {
            throw ApiException.validation("邮件服务暂时不可用，请稍后再试");
        }
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String key = CODE_PREFIX + id + ":" + purpose;
        redis.opsForValue().set(key, codeHash(user, purpose, code), TTL);
        redis.delete(key + ":attempts");
        BrandedMail.sendCode(sender.getObject(),from,emails.getFirst(),"AlgoHub：确认账号安全操作",
                "确认账号安全操作","操作：" + label(purpose),code,5,
                "此验证码只用于本次操作。",
                "若非本人操作，请勿提供验证码，并检查登录设备及邮箱、GitHub 的安全设置。");
    }

    private static String label(String purpose) {
        return switch (purpose) {
            case "DELETE_ACCOUNT" -> "注销账号";
            case "BIND_GITHUB" -> "绑定 GitHub";
            case "UNBIND_GITHUB" -> "解绑 GitHub";
            default -> "第一次绑定登录邮箱";
        };
    }

    private static String codeHash(AppUser user, String purpose, String code) {
        return EmailLoginService.hash(user.getId() + ":" + purpose + ":" + IdentitySecurity.version(user) + ":" + code);
    }

    /** 核对密码或邮箱验证码，通过后发凭证 */
    public String verify(long id, String purpose, String password, String code) {
        purpose(purpose);
        limits.check("security-proof:user", Long.toString(id), 10, 300);
        AppUser user = activeUser(id);
        if (password != null && !password.isBlank()) {
            if (!AuthService.passwordEnabled(user) || !encoder.matches(password, user.getPasswordHash())) {
                throw ApiException.validation("密码不正确");
            }
        } else {
            String key = CODE_PREFIX + id + ":" + purpose;
            Long ok = redis.execute(CHECK_CODE, List.of(key, key + ":attempts"), codeHash(user, purpose, code == null ? "" : code));
            if (!Long.valueOf(1).equals(ok)) {
                throw ApiException.validation("验证码不正确或已失效");
            }
        }
        return grant(user, purpose);
    }

    public String grant(AppUser user, String purpose) {
        purpose(purpose);
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        try {
            redis.opsForValue().set(PROOF_PREFIX + token,
                    json.writeValueAsString(new Proof(user.getId(), purpose, IdentitySecurity.version(user))), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建身份凭证", e);
        }
        return token;
    }

    /** 用掉凭证。调用方要在事务里：这里锁住用户行，一直锁到敏感操作做完 */
    public void consume(long id, String purpose, String token) {
        purpose(purpose);
        AppUser user = users.lockIdentity(id);
        if (token == null || token.length() > 100) {
            throw ApiException.forbidden("请先确认本人身份");
        }
        String raw = redis.opsForValue().getAndDelete(PROOF_PREFIX + token);
        if (raw != null && user != null && "ACTIVE".equals(user.getStatus())) {
            try {
                Proof proof = json.readValue(raw, Proof.class);
                if (proof.userId() == id && proof.purpose().equals(purpose)
                        && proof.version().equals(IdentitySecurity.version(user))) {
                    return;
                }
            } catch (Exception ignored) {
                // 解析不了按失效处理
            }
        }
        throw ApiException.forbidden("身份确认已失效，请重新确认");
    }
}
