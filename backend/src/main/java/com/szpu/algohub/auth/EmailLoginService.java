package com.szpu.algohub.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.mail.BrandedMail;

@Service
public class EmailLoginService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> mail;
    private final AppUserMapper users;
    private final EmailCodeGuard guard;
    private final String from;
    private final SecureRandom random=new SecureRandom();
    public EmailLoginService(JdbcTemplate jdbc,StringRedisTemplate redis,ObjectProvider<JavaMailSender> mail,
        AppUserMapper users,EmailCodeGuard guard,@Value("${algohub.email.from:}") String from) {
        this.jdbc=jdbc;this.redis=redis;this.mail=mail;this.users=users;this.guard=guard;this.from=from;
    }
    public boolean enabled() { return mail.getIfAvailable()!=null && !from.isBlank(); }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @Transactional
    public void send(String rawEmail,String ip) {
        if(!enabled()) throw ApiException.validation("邮箱登录暂未启用，请管理员配置发件服务");
        String email=EmailIdentityService.normalize(rawEmail);
        guard.requireNotLocked(email);
        // IP/全局配额由 AuthRateLimitConfig 统一控制；保留每收件人60秒冷却，避免校园共享IP互相挤占。
        if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("algohub:email-login:address:"+hash(email),"1",Duration.ofSeconds(60))))
            throw ApiException.validation("请在 60 秒后重新获取验证码");
        guard.countSend(email);
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        jdbc.update("""
            insert into email_login_challenge(email,code_hash,expires_at) values (?,?,now()+interval '10 minutes')
            on conflict(email) do update set code_hash=excluded.code_hash,expires_at=excluded.expires_at,sent_at=now()
            """,email,hash("LOGIN:"+email+":"+code));
        try {BrandedMail.sendCode(mail.getObject(),from,email,"AlgoHub：登录验证码","登录 AlgoHub",
                "使用以下验证码登录。首次验证将创建平台账号，不会自动加入学校。",code,10,
                "同一邮箱 24 小时窗口内累计输错 10 次将锁定 15 分钟。",
                "若非本人操作，请忽略此邮件，切勿向他人提供验证码。");}
        catch(org.springframework.mail.MailException e){throw new IllegalStateException("发送登录邮件失败，请检查发件服务配置");}
    }
    @Transactional(noRollbackFor=ApiException.class)
    public AppUser verify(String rawEmail,String code) {
        String email=EmailIdentityService.normalize(rawEmail);
        guard.requireNotLocked(email);
        // Match identity mutations: user row first, then challenges. Recheck after waiting.
        AppUser owner=users.findByVerifiedEmail(email);
        if(owner!=null) {
            users.lockIdentity(owner.getId());
            AppUser current=users.findByVerifiedEmail(email);
            if(current==null || !current.getId().equals(owner.getId()))
                throw ApiException.validation("邮箱归属已变化，请重新获取验证码");
            guard.requireNotLocked(email);
        }
        var rows=jdbc.queryForList("select * from email_login_challenge where email=? for update",email);
        if(rows.isEmpty()) throw ApiException.validation("验证码不正确或已失效");
        var row=rows.get(0);
        if(((Number)row.get("attempts")).intValue()>=EmailCodeGuard.MAX_FAILURES) {
            // 跨过 24 小时窗口累计满的旧记录：作废，下次发码从头计数（总次数仍受 EmailCodeGuard 约束）
            jdbc.update("delete from email_login_challenge where email=?",email);
            throw ApiException.validation("验证码不正确或已失效，请重新获取");
        }
        if(((java.sql.Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))
            throw ApiException.validation("验证码不正确或已失效");
        jdbc.update("update email_login_challenge set attempts=attempts+1 where email=?",email);
        if(!MessageDigest.isEqual(hash("LOGIN:"+email+":"+code).getBytes(StandardCharsets.UTF_8),((String)row.get("code_hash")).getBytes(StandardCharsets.UTF_8))) {
            if(guard.recordFailure(email)) {
                // 触发锁定：作废该邮箱所有待验证的码，锁定期结束后重新发码从头计数
                jdbc.update("delete from email_login_challenge where email=?",email);
                jdbc.update("delete from password_reset_challenge where email=?",email);
                throw new ApiException(com.szpu.algohub.common.ErrorKind.RATE_LIMITED,EmailCodeGuard.LOCKED_MESSAGE);
            }
            throw ApiException.validation("验证码不正确或已失效");
        }
        AppUser user=users.findByVerifiedEmail(email);
        if(user==null) {
            user=new AppUser(); user.setUsername("mail_"+UUID.randomUUID().toString().replace("-","").substring(0,20));
            user.setDisplayName("新同学_" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT));
            user.setNicknameNeedsReview(true);
            user.setEmail(email);user.setRole("STUDENT");user.setStatus("ACTIVE");
            users.insert(user);
            jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",user.getId(),email);
        }
        if(!"ACTIVE".equals(user.getStatus())) throw ApiException.forbidden("账号不可用，请联系管理员");
        jdbc.update("delete from email_login_challenge where email=?",email);
        return user;
    }
}
