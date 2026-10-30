package com.szpu.algohub.auth;

import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.mail.BrandedMail;

@Service
public class PasswordResetService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> sender;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final TokenStore tokens;
    private final AuthService auth;
    private final EmailCodeGuard guard;
    private final String from;
    public PasswordResetService(JdbcTemplate jdbc,StringRedisTemplate redis,ObjectProvider<JavaMailSender> sender,
        AppUserMapper users,PasswordEncoder encoder,TokenStore tokens,AuthService auth,EmailCodeGuard guard,@Value("${algohub.email.from:}") String from) {
        this.jdbc=jdbc;this.redis=redis;this.sender=sender;this.users=users;this.encoder=encoder;this.tokens=tokens;this.auth=auth;this.guard=guard;this.from=from;
    }
    @Transactional
    public void send(String rawEmail,String ip) {
        if(sender.getIfAvailable()==null || from.isBlank()) throw ApiException.validation("密码找回暂不可用，请管理员配置发件服务");
        String email=EmailIdentityService.normalize(rawEmail);
        guard.requireNotLocked(email);
        // IP/全局配额在入口统一控制，不能再让同一校园出口每分钟只能服务一人。
        for(String key:List.of("email:"+email))
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("algohub:reset:"+EmailLoginService.hash(key),"1",Duration.ofSeconds(60))))
                throw ApiException.validation("发送过于频繁，请在 60 秒后重试");
        // 不论邮箱是否注册都计数，避免借配额差异探测账号是否存在
        guard.countSend(email);
        AppUser user=users.findByVerifiedEmail(email);
        if(user==null || !"ADMIN".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus())) return;
        users.lockIdentity(user.getId());
        AppUser current=users.findByVerifiedEmail(email);
        if(current==null || !"ADMIN".equals(current.getRole()) || !current.getId().equals(user.getId()) || !"ACTIVE".equals(current.getStatus())) return;
        String code=String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1000000));
        jdbc.update("insert into password_reset_challenge(email,user_id,code_hash,expires_at) values (?,?,?,now()+interval '10 minutes') on conflict(email) do update set user_id=excluded.user_id,code_hash=excluded.code_hash,expires_at=excluded.expires_at",email,user.getId(),EmailLoginService.hash("RESET:"+email+":"+code));
        BrandedMail.sendCode(sender.getObject(),from,email,"AlgoHub：重置密码","重置账号密码",
                "使用以下验证码重置管理员账号密码。",code,10,
                "同一邮箱 24 小时窗口内累计输错 10 次将锁定 15 分钟。重置成功后，旧设备将退出登录。",
                "若非本人操作，请忽略此邮件，切勿向他人提供验证码。");
    }
    @Transactional(noRollbackFor=ApiException.class)
    public void reset(String rawEmail,String code,String password,String ip,String agent) {
        String email=EmailIdentityService.normalize(rawEmail);
        guard.requireNotLocked(email);
        // 和改密码、换邮箱、退出所有设备同样的加锁顺序：先锁用户行
        AppUser owner=users.findByVerifiedEmail(email);
        if(owner==null) throw ApiException.validation("验证码不正确或已失效");
        users.lockIdentity(owner.getId());
        AppUser user=users.findByVerifiedEmail(email);
        if(user==null || !"ADMIN".equals(user.getRole()) || !user.getId().equals(owner.getId()) || !"ACTIVE".equals(user.getStatus()))
            throw ApiException.validation("验证码不正确或已失效");
        var rows=jdbc.queryForList("select * from password_reset_challenge where email=? for update",email);
        if(rows.isEmpty()) throw ApiException.validation("验证码不正确或已失效");
        var row=rows.getFirst();
        if(((Number)row.get("attempts")).intValue()>=EmailCodeGuard.MAX_FAILURES) {
            jdbc.update("delete from password_reset_challenge where email=?",email);
            throw ApiException.validation("验证码不正确或已失效，请重新获取");
        }
        if(!((java.sql.Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now())) throw ApiException.validation("验证码不正确或已失效");
        jdbc.update("update password_reset_challenge set attempts=attempts+1 where email=?",email);
        if(!MessageDigest.isEqual(EmailLoginService.hash("RESET:"+email+":"+code).getBytes(StandardCharsets.UTF_8),row.get("code_hash").toString().getBytes(StandardCharsets.UTF_8))) {
            if(guard.recordFailure(email)) {
                jdbc.update("delete from password_reset_challenge where email=?",email);
                jdbc.update("delete from email_login_challenge where email=?",email);
                throw new ApiException(com.szpu.algohub.common.ErrorKind.RATE_LIMITED,EmailCodeGuard.LOCKED_MESSAGE);
            }
            throw ApiException.validation("验证码不正确或已失效");
        }
        if(!user.getId().equals(((Number)row.get("user_id")).longValue())) throw ApiException.validation("验证码不正确或已失效");
        users.updatePassword(user.getId(),encoder.encode(password));
        users.invalidateIssuedTokens(user.getId());tokens.revokeAllSessions(user.getId());
        auth.invalidateEmailChallenges(user.getId());
        auth.audit(user.getId(),user.getUsername(),"PASSWORD_RESET",ip,agent,"通过已验证邮箱重置密码");
    }
}
