package com.szpu.algohub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.mail.BrandedMail;

@Service
public class EmailIdentityService {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final IdentitySecurity identities;
    /** 和登录码、找回密码码共用：按收件地址计输错次数、每天发信上限 */
    private final EmailCodeGuard guard;
    private final SecureRandom random=new SecureRandom();
    public EmailIdentityService(JdbcTemplate jdbc, ObjectProvider<JavaMailSender> sender,
        @Value("${algohub.email.from:}") String from, IdentitySecurity identities, EmailCodeGuard guard) {
        this.identities=identities;
        this.guard=guard;
        this.jdbc=jdbc; this.sender=sender; this.from=from;
    }
    static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    public Map<String,Object> status(Long userId) {
        var rows=jdbc.queryForList("select email, verified_at as \"verifiedAt\" from verified_login_email where user_id=?",userId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",sender.getIfAvailable()!=null && !from.isBlank());
        result.put("email",rows.isEmpty()?null:rows.get(0).get("email"));
        result.put("verifiedAt",rows.isEmpty()?null:rows.get(0).get("verifiedAt"));
        return result;
    }
    private static String hash(Long user,String email,String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((user+":"+email+":"+code).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public void send(Long userId, String email) {
        JavaMailSender mail=sender.getIfAvailable();
        if(mail==null || from.isBlank()) throw ApiException.validation("邮箱验证暂未启用，请管理员配置发件服务");
        email=normalize(email);
        // 同一用户串行发送，防止并发绕过冷却。
        jdbc.queryForObject("select id from app_user where id=? for update",Long.class,userId);
        var existing=jdbc.queryForList("select email from verified_login_email where user_id=?",userId);
        String previous=existing.isEmpty()?null:(String)existing.getFirst().get("email");
        if(email.equals(previous)) throw ApiException.validation("该邮箱已经绑定，无需重复验证");
        Integer recent=jdbc.queryForObject("select count(*) from email_verification_challenge where user_id=? and sent_at > now()-interval '60 seconds'",Integer.class,userId);
        if(recent>0) throw ApiException.validation("请在 60 秒后重试");
        guard.requireNotLocked(email);
        guard.countSend(email);
        if(previous!=null) {
            guard.requireNotLocked(previous);
            guard.countSend(previous);
        }
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        String previousCode=previous==null?null:String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        jdbc.update("""
            insert into email_verification_challenge(user_id,email,code_hash,expires_at,previous_email,previous_code_hash)
            values (?,?,?,now()+interval '10 minutes',?,?)
            on conflict(user_id) do update set email=excluded.email,code_hash=excluded.code_hash,
              expires_at=excluded.expires_at,sent_at=now(),attempts=0,
              previous_email=excluded.previous_email,previous_code_hash=excluded.previous_code_hash
            """,userId,email,hash(userId,email,code),previous,previous==null?null:hash(userId,"OLD:"+previous+":"+email,previousCode));
        try {
            if(previous!=null) {
                BrandedMail.sendCode(mail,from,previous,"AlgoHub：确认更换登录邮箱","确认更换登录邮箱",
                        "有人正在更换你在 AlgoHub 的登录邮箱。请用原邮箱验证码确认。",previousCode,10,
                        "需要同时验证原邮箱和新邮箱才能完成更换。",
                        "若非本人操作，请忽略此邮件，切勿向他人提供验证码。");
            }
            BrandedMail.sendCode(mail,from,email,"AlgoHub：验证登录邮箱","验证登录邮箱",
                    "使用以下验证码绑定或更换登录邮箱。",code,10,
                    "完成验证后，此邮箱可用于登录 AlgoHub。",
                    "若非本人操作，请忽略此邮件，切勿向他人提供验证码。");
        }
        catch(org.springframework.mail.MailException e) { throw new IllegalStateException("发送验证邮件失败，请检查发件服务配置"); }
    }
    @Transactional(noRollbackFor=ApiException.class)
    public boolean verify(Long userId,String email,String code) {
        return verify(userId,email,code,null);
    }
    /**
     * 核对后绑定。返回 true 表示是换邮箱：所有设备的登录都已撤销（调用方给当前设备重新签发）；
     * 第一次绑定只是补上登录邮箱，不撤销。
     */
    @Transactional(noRollbackFor=ApiException.class)
    public boolean verify(Long userId,String email,String code,String previousCode) {
        email=normalize(email);
        jdbc.queryForObject("select id from app_user where id=? for update",Long.class,userId);
        var rows=jdbc.queryForList("select * from email_verification_challenge where user_id=? for update",userId);
        if(rows.isEmpty()) throw ApiException.validation("请先获取验证码");
        var row=rows.get(0);
        if(((java.sql.Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()) || ((Number)row.get("attempts")).intValue()>=5)
            throw ApiException.validation("验证码已失效，请重新获取");
        guard.requireNotLocked(email);
        jdbc.update("update email_verification_challenge set attempts=attempts+1 where user_id=?",userId);
        var existing=jdbc.queryForList("select email from verified_login_email where user_id=?",userId);
        String previous=existing.isEmpty()?null:(String)existing.getFirst().get("email");
        if(!Objects.equals(previous,row.get("previous_email"))) throw ApiException.validation("邮箱归属已变化，请重新获取验证码");
        if(previous!=null && (previousCode==null || row.get("previous_code_hash")==null || !MessageDigest.isEqual(
                hash(userId,"OLD:"+previous+":"+email,previousCode).getBytes(StandardCharsets.UTF_8),
                row.get("previous_code_hash").toString().getBytes(StandardCharsets.UTF_8)))) {
            // 拿着别人登录态想换掉邮箱的人猜的是原邮箱的码：记在原邮箱上
            if(previousCode!=null) failed(userId,previous);
            throw ApiException.validation("原邮箱验证码不正确");
        }
        if(!email.equals(row.get("email")) || !MessageDigest.isEqual(hash(userId,email,code).getBytes(StandardCharsets.UTF_8),((String)row.get("code_hash")).getBytes(StandardCharsets.UTF_8))) {
            failed(userId,email);
            throw ApiException.validation("验证码不正确");
        }
        Integer occupied=jdbc.queryForObject("select count(*) from verified_login_email where email=? and user_id<>?",Integer.class,email,userId);
        if(occupied>0) throw ApiException.conflict("此邮箱无法绑定，请使用其他邮箱");
        // 绑定归属改变后，之前签发的登录验证码不能继续沿用。
        jdbc.update("delete from email_login_challenge where email=? or email in (select email from verified_login_email where user_id=?)",email,userId);
        jdbc.update("delete from password_reset_challenge where user_id=? or email=?",userId,email);
        jdbc.update("""
            insert into verified_login_email(user_id,email) values (?,?)
            on conflict(user_id) do update set email=excluded.email,verified_at=now()
            """,userId,email);
        jdbc.update("delete from email_verification_challenge where user_id=?",userId);
        if(previous==null) {
            // First verified login email is the default contact address, without replacing an existing contact.
            jdbc.update("update app_user set email=?,updated_at=now() where id=? and (email is null or trim(email)='')",email,userId);
            jdbc.update("insert into auth_event(user_id,event,detail) values(?,'LOGIN_EMAIL_VERIFIED','已验证登录邮箱')",userId);
            return false;
        }
        identities.changed(userId);
        jdbc.update("insert into auth_event(user_id,event,detail) values(?,'LOGIN_EMAIL_CHANGED','登录邮箱已更换，其他设备已退出')",userId);
        return true;
    }
    /** 记一次猜错；这次触发了锁定就作废这个用户待验证的码 */
    private void failed(Long userId,String email) {
        if(guard.recordFailure(email)) {
            jdbc.update("delete from email_verification_challenge where user_id=?",userId);
            throw new ApiException(com.szpu.algohub.common.ErrorKind.RATE_LIMITED,EmailCodeGuard.LOCKED_MESSAGE);
        }
    }
    @Transactional
    public void remove(Long userId) {
        throw ApiException.validation("登录邮箱为必需身份信息，不能直接移除；请验证原邮箱和新邮箱后更换");
    }
}
