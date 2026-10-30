package com.szpu.algohub.auth;

import java.time.Duration;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.identity.GithubOauthService.GithubUser;
import com.szpu.algohub.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import com.szpu.algohub.mail.BrandedMail;

/** Browser-only, short-lived continuation of an already verified GitHub identity. Call inside a transaction. */
@Service
public class PendingGithubLink {
    public static final String COOKIE="algohub_github_link";
    private static final String PREFIX="algohub:github-link:";
    public record Pending(long userId,String email,String version,GithubUser github) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AppUserMapper users;
    private final AuthRateLimit limiter;
    private final EmailCodeGuard guard;
    private final AuthService auth;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final SecureRandom random=new SecureRandom();
    public PendingGithubLink(StringRedisTemplate redis,ObjectMapper json,AppUserMapper users,AuthRateLimit limiter,
            EmailCodeGuard guard,AuthService auth,ObjectProvider<JavaMailSender> sender,@Value("${algohub.email.from:}") String from) {
        this.redis=redis;this.json=json;this.users=users;this.limiter=limiter;this.guard=guard;this.auth=auth;this.sender=sender;this.from=from;
    }
    private String key(String secret) {
        if(secret==null || secret.length()!=72) throw ApiException.forbidden("关联已过期或未发起，请重新使用 GitHub 登录");
        return PREFIX+EmailLoginService.hash(secret);
    }
    public String create(AppUser target,GithubUser github) {
        var user=users.lockIdentity(target.getId());
        var owner=users.findByVerifiedEmail(github.verifiedEmail());
        if(user==null || owner==null || !owner.getId().equals(user.getId()) || !"ACTIVE".equals(user.getStatus()))
            throw ApiException.forbidden("账号身份已变化，请重新登录");
        limiter.check("github-link:create",user.getId().toString(),10,600);
        String secret=UUID.randomUUID().toString()+UUID.randomUUID();
        try { redis.opsForValue().set(key(secret),json.writeValueAsString(new Pending(user.getId(),github.verifiedEmail(),IdentitySecurity.version(user),github)),Duration.ofMinutes(10)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        return secret;
    }
    public Pending require(String secret,Long currentId) {
        String k=key(secret),raw=redis.opsForValue().get(k);
        Pending pending;
        try { pending=json.readValue(raw,Pending.class); }
        catch(Exception e) { throw ApiException.forbidden("关联已过期或已完成，请重新使用 GitHub 登录"); }
        if(currentId!=null && currentId!=pending.userId()) throw ApiException.forbidden("当前登录账号与待关联账号不一致，请退出后重新授权");
        AppUser user=users.lockIdentity(pending.userId());
        AppUser owner=users.findByVerifiedEmail(pending.email());
        if(user==null || !"ACTIVE".equals(user.getStatus()) || owner==null || owner.getId()!=pending.userId()
                || !IdentitySecurity.version(user).equals(pending.version()) || !Objects.equals(raw,redis.opsForValue().get(k)))
            throw ApiException.forbidden("账号身份已变化或关联已失效，请重新使用 GitHub 登录");
        return pending;
    }
    public Map<String,Object> context(String secret,Long currentId) {
        var p=require(secret,currentId);
        int at=p.email().indexOf('@');
        String masked=p.email().substring(0,Math.min(2,at))+"***"+p.email().substring(at);
        return Map.of("maskedEmail",masked,"githubLogin",Objects.toString(p.github().login(),"GitHub"),
                "hasPassword",AuthService.passwordEnabled(users.findById(p.userId())));
    }
    public void send(String secret,Long currentId) {
        var p=require(secret,currentId);
        var mail=sender.getIfAvailable();
        if(mail==null || from.isBlank()) throw ApiException.validation("邮箱验证暂未启用，请使用原账号密码");
        guard.requireNotLocked(p.email());
        limiter.check("github-link:send",Long.toString(p.userId()),1,60,"请在 60 秒后重新获取验证码");
        guard.countSend(p.email());
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        String codeKey=key(secret)+":code";
        redis.opsForValue().set(codeKey,EmailLoginService.hash(secret+":"+code),Duration.ofMinutes(5));
        try { BrandedMail.sendCode(mail,from,p.email(),"AlgoHub：确认关联 GitHub","确认关联 GitHub",
                "你正在关联 GitHub 账号 " + p.github().login() + "。",code,5,
                "关联后，该 GitHub 账号可以登录你的 AlgoHub 账号。",
                "若非本人操作，请忽略此邮件，切勿向他人提供验证码。"); }
        catch(org.springframework.mail.MailException e) { redis.delete(codeKey);throw new IllegalStateException("发送验证邮件失败，请稍后重试"); }
    }
    public Pending verify(String secret,Long currentId,String method,String code,String password,String ip,String agent) {
        var p=require(secret,currentId);
        limiter.check("github-link:attempt",key(secret),5,600,"本次关联验证次数已用完，请重新使用 GitHub 登录");
        limiter.check("github-link:account",Long.toString(p.userId()),10,600,"该账号关联验证过于频繁，请 10 分钟后再试");
        if("email".equals(method)) {
            guard.requireNotLocked(p.email());
            String expected=redis.opsForValue().get(key(secret)+":code");
            if(code==null || expected==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),EmailLoginService.hash(secret+":"+code).getBytes(StandardCharsets.UTF_8))) {
                if(guard.recordFailure(p.email())) guard.requireNotLocked(p.email());
                throw ApiException.validation("验证码不正确或已失效");
            }
        } else if("password".equals(method)) {
            if(password==null || password.isBlank()) throw ApiException.validation("请输入原账号密码");
            // Username comes exclusively from the locked server-side target.
            auth.authenticatePassword(users.findById(p.userId()).getUsername(),password,ip,agent);
        } else throw ApiException.validation("无效的验证方式");
        if(redis.opsForValue().getAndDelete(key(secret))==null) throw ApiException.forbidden("关联已过期或已完成，请重新授权");
        redis.delete(key(secret)+":code");
        return p;
    }
}
