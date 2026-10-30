package com.szpu.algohub.auth;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.MimeMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.szpu.algohub.school.SchoolService;
import com.szpu.algohub.common.ApiException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="algohub.email.from=security@example.test") @AutoConfigureMockMvc @Transactional
class IdentityBoundaryTest {
    @Autowired SecurityActionService actions; @Autowired OauthBrowserFlow flows;
    @Autowired IdentitySecurity identities; @Autowired AppUserMapper users; @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder; @Autowired MockMvc mvc;
    @Autowired SchoolService schools;
    @Autowired EmailIdentityService emailIdentity;
    @Autowired TokenStore tokens;
    @MockitoBean JavaMailSender mail;
    AppUser user;
    @BeforeEach void setup(){
        MailTestSupport.stubMimeMessages(mail);
        user=new AppUser();user.setUsername("secure-"+UUID.randomUUID().toString().substring(0,12));
        user.setDisplayName("安全测试");user.setRole("STUDENT");user.setStatus("ACTIVE");
        user.setPasswordHash(encoder.encode("test-identity-password"));users.insert(user);
        jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",user.getId(),user.getUsername()+"@example.test");
    }
    String access(){return auth.issueSession(users.findById(user.getId()),"test","test","TEST").view().accessToken();}
    @Test void proofIsOneUseAndCannotChangePurposeOrUser(){
        String grant=actions.grant(users.findById(user.getId()),"DELETE_ACCOUNT");
        assertThatThrownBy(()->actions.consume(user.getId(),"BIND_GITHUB",grant)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->actions.consume(user.getId(),"DELETE_ACCOUNT",grant)).isInstanceOf(ApiException.class);
        String next=actions.grant(users.findById(user.getId()),"DELETE_ACCOUNT");
        actions.consume(user.getId(),"DELETE_ACCOUNT",next);
        assertThatThrownBy(()->actions.consume(user.getId(),"DELETE_ACCOUNT",next)).isInstanceOf(ApiException.class);
    }
    @Test void identityChangeInvalidatesProofBrowserFlowAndAccessToken() throws Exception {
        String proof=actions.grant(user,"DELETE_ACCOUNT"),secret=flows.create("old-state",user.getId(),"BIND",null),access=access();
        identities.changed(user.getId());
        assertThatThrownBy(()->actions.consume(user.getId(),"DELETE_ACCOUNT",proof)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->flows.consume("old-state",secret,user.getId())).isInstanceOf(ApiException.class);
        mvc.perform(get("/api/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void oauthRejectsOtherBrowserOtherUserAndReplay(){
        String secret=flows.create("bound-state",user.getId(),"BIND",null);
        assertThatThrownBy(()->flows.consume("bound-state","attacker-browser",user.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->flows.consume("bound-state",secret,null)).isInstanceOf(ApiException.class);
        assertThat(flows.consume("bound-state",secret,user.getId()).userId()).isEqualTo(user.getId());
        assertThatThrownBy(()->flows.consume("bound-state",secret,user.getId())).isInstanceOf(ApiException.class);
    }
    @Test void loginCallbackCannotBeInjectedWithoutBrowserCookie() throws Exception {
        mvc.perform(post("/api/auth/github/callback").contentType("application/json").content("{\"state\":\"injected\",\"code\":\"code\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void passwordlessDeletionRequiresExplicitProofAndUsername() throws Exception {
        jdbc.update("update app_user set password_hash=null where id=?",user.getId());
        String access=access();
        mvc.perform(post("/api/me/delete").header("Authorization","Bearer "+access).contentType("application/json")
            .content("{\"confirmUsername\":\""+user.getUsername()+"\"}" )).andExpect(status().isForbidden());
        String proof=actions.grant(users.findById(user.getId()),"DELETE_ACCOUNT");
        mvc.perform(post("/api/me/delete").header("Authorization","Bearer "+access).contentType("application/json")
            .content("{\"confirmUsername\":\""+user.getUsername()+"\",\"actionToken\":\""+proof+"\"}" )).andExpect(status().isOk());
        assertThat(users.findById(user.getId())).isNull();
    }
    @Test void emailProofIsOperationSpecificAndLocksAfterFiveGuesses(){
        actions.send(user.getId(),"BIND_GITHUB");
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        String code=MailTestSupport.code(captor.getValue()),wrong=code.equals("000000")?"111111":"000000";
        for(int i=0;i<5;i++)assertThatThrownBy(()->actions.verify(user.getId(),"BIND_GITHUB",null,wrong)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->actions.verify(user.getId(),"BIND_GITHUB",null,code)).isInstanceOf(ApiException.class);
    }
    /** 学校规则（2026-09-29 定）：邀请码对上直接加入，本人之后还能改年级班级；管理员也能改，并留痕 */
    @Test void invitationJoinsDirectlyAndAdminCanCorrectTheProfile(){
        long school=jdbc.queryForObject("select id from school order by id limit 1",Long.class);
        String invite=jdbc.queryForObject("select invite_code from school where id=?",String.class,school);
        var member=schools.selectSchool(user.getId(),school,invite);
        assertThat(member.getStatus()).isEqualTo("APPROVED");
        assertThat(schools.updateProfile(user.getId(),member.getId(),2024,"一班","security-student").getClassName()).isEqualTo("一班");
        long admin=jdbc.queryForObject("select id from app_user where role='ADMIN' order by id limit 1",Long.class);
        assertThat(schools.adminUpdateProfile(member.getId(),2025,"二班","security-student",admin).getEnrollmentYear()).isEqualTo(2025);
        assertThat(jdbc.queryForObject("select count(*) from auth_event where user_id=? and event='ADMIN_MEMBER_PROFILE_CHANGED'",Integer.class,admin)).isPositive();
    }
    /** 绑定/更换登录邮箱的验证码和登录码一样计输错：重发不清零，累计 10 次锁定这个邮箱 */
    @Test void bindingCodeGuessesLockTheTargetEmailAcrossResends(){
        jdbc.update("delete from verified_login_email where user_id=?",user.getId());
        String target="guess-"+user.getUsername()+"@example.test";
        for(int round=0;round<2;round++){
            jdbc.update("delete from email_verification_challenge where user_id=?",user.getId());
            emailIdentity.send(user.getId(),target);
            for(int i=0;i<5;i++){
                String wrong=String.format("%06d",i+round*5);
                try{emailIdentity.verify(user.getId(),target,wrong,null);}catch(ApiException ignored){}
            }
        }
        assertThatThrownBy(()->emailIdentity.send(user.getId(),target)).hasMessageContaining("锁定");
        jdbc.update("delete from email_verification_challenge where user_id=?",user.getId());
        assertThatThrownBy(()->emailIdentity.send(user.getId(),target)).hasMessageContaining("锁定");
    }
    @Test void proofCannotBeUsedByAnotherAccount(){
        String proof=actions.grant(users.findById(user.getId()),"DELETE_ACCOUNT");
        long admin=jdbc.queryForObject("select id from app_user where role='ADMIN' order by id limit 1",Long.class);
        assertThatThrownBy(()->actions.consume(admin,"DELETE_ACCOUNT",proof)).isInstanceOf(ApiException.class);
    }
    @Test void githubBindingAndFirstEmailRequireProofEvenWithStolenAccess() throws Exception {
        // 注册超过半小时的账号：第一次绑登录邮箱也要先确认本人（先改好，MyBatis 会缓存之后读到的用户）
        jdbc.update("update app_user set created_at=now()-interval '1 hour' where id=?",user.getId());
        String access=access();
        mvc.perform(post("/api/auth/github/bind-url").header("Authorization","Bearer "+access).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/github/authorize-url").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        jdbc.update("delete from verified_login_email where user_id=?",user.getId());
        mvc.perform(post("/api/me/login-email/verify").header("Authorization","Bearer "+access).contentType("application/json")
            .content("{\"email\":\"attacker@example.test\",\"code\":\"123456\"}")).andExpect(status().isForbidden());
    }
    /** 刚注册的账号在引导里绑第一个登录邮箱：不用再确认本人，也不撤销登录 */
    @Test void freshAccountBindsFirstEmailWithoutProofAndKeepsSession() throws Exception {
        jdbc.update("delete from verified_login_email where user_id=?",user.getId());
        String access=access(),target="first-"+user.getUsername()+"@example.test";
        emailIdentity.send(user.getId(),target);
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        mvc.perform(post("/api/me/login-email/verify").header("Authorization","Bearer "+access).contentType("application/json")
            .content("{\"email\":\""+target+"\",\"code\":\""+code(captor.getValue())+"\"}")).andExpect(status().isOk());
        assertThat(emailIdentity.status(user.getId()).get("email")).isEqualTo(target);
        mvc.perform(get("/api/me").header("Authorization","Bearer "+access)).andExpect(status().isOk());
    }
    @Test void correctEmailCodeProducesAnOperationProof(){
        actions.send(user.getId(),"DELETE_ACCOUNT");
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        String code=MailTestSupport.code(captor.getValue());
        String proof=actions.verify(user.getId(),"DELETE_ACCOUNT",null,code);
        actions.consume(user.getId(),"DELETE_ACCOUNT",proof);
        assertThatThrownBy(()->actions.verify(user.getId(),"DELETE_ACCOUNT",null,code)).isInstanceOf(ApiException.class);
    }
    @Test void emailChangeRequiresBothMailboxesAndRevokesExistingSessions() throws Exception {
        String target="new-"+user.getUsername()+"@example.test";
        var session=auth.issueSession(users.findById(user.getId()),"test","test","TEST");
        emailIdentity.send(user.getId(),target);
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail,times(2)).send(captor.capture());
        String oldCode=code(captor.getAllValues().get(0)),newCode=code(captor.getAllValues().get(1));
        assertThatThrownBy(()->emailIdentity.verify(user.getId(),target,newCode,null)).hasMessageContaining("原邮箱");
        assertThat(emailIdentity.status(user.getId()).get("email")).isEqualTo(user.getUsername()+"@example.test");
        emailIdentity.verify(user.getId(),target,newCode,oldCode);
        assertThat(emailIdentity.status(user.getId()).get("email")).isEqualTo(target);
        mvc.perform(get("/api/me").header("Authorization","Bearer "+session.view().accessToken())).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->auth.refresh(session.refreshToken(),"test","test")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from auth_event where user_id=? and event='LOGIN_EMAIL_CHANGED'",Integer.class,user.getId())).isEqualTo(1);
    }
    @Test void identityChangesCancelPendingEmailAndPasswordRecovery() {
        String email=user.getUsername()+"@example.test";
        emailIdentity.send(user.getId(),"new-"+email);
        jdbc.update("insert into password_reset_challenge(email,user_id,code_hash,expires_at) values(?,?,?,now()+interval '5 minutes')",email,user.getId(),"0".repeat(64));
        jdbc.update("insert into email_login_challenge(email,code_hash,expires_at) values(?,?,now()+interval '5 minutes')",email,"0".repeat(64));
        identities.changed(user.getId());
        assertThat(jdbc.queryForObject("select count(*) from email_verification_challenge where user_id=?",Integer.class,user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from password_reset_challenge where user_id=?",Integer.class,user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email=?",Integer.class,email)).isZero();
    }
    private String code(MimeMessage mail) { return MailTestSupport.code(mail); }
    @Test void lateOldVersionRefreshTokenCannotRestoreRevokedLogin() {
        String oldVersion=IdentitySecurity.version(users.findById(user.getId()));
        identities.changed(user.getId());
        // Reproduce the dangerous ordering: old login issues after the revocation sweep.
        var late=tokens.issueRefreshToken(user.getId(),"late request",oldVersion);
        assertThatThrownBy(()->auth.refresh(late.value(),"test","test")).isInstanceOf(ApiException.class);
        assertThat(tokens.userIdOfRefreshToken(late.value())).isNull();
        var current=auth.issueSession(users.findById(user.getId()),"test","test","TEST");
        var refreshed=auth.refresh(current.refreshToken(),"test","test");
        assertThat(tokens.matchesIdentity(refreshed.refreshToken(),IdentitySecurity.version(users.findById(user.getId())))).isTrue();
    }
    @Test void staleAuthenticationCannotBeUpgradedToCurrentIdentity() {
        AppUser previouslyAuthenticated=users.findById(user.getId());
        identities.changed(user.getId());
        assertThatThrownBy(()->auth.issueSession(previouslyAuthenticated,"test","test","TEST"))
            .isInstanceOf(ApiException.class).hasMessageContaining("身份已改变");
    }
    @Test void logoutAllAlsoCancelsOutstandingRecoveryAndIdentityProofs() {
        String email=user.getUsername()+"@example.test";
        emailIdentity.send(user.getId(),"new-"+email);
        jdbc.update("insert into password_reset_challenge(email,user_id,code_hash,expires_at) values(?,?,?,now()+interval '5 minutes')",email,user.getId(),"0".repeat(64));
        jdbc.update("insert into email_login_challenge(email,code_hash,expires_at) values(?,?,now()+interval '5 minutes')",email,"0".repeat(64));
        String proof=actions.grant(users.findById(user.getId()),"DELETE_ACCOUNT");
        String browser=flows.create("logout-"+user.getId(),user.getId(),"BIND",null);
        var session=auth.issueSession(users.findById(user.getId()),"test","test","TEST");
        assertThat(auth.logoutAll(user.getId(),"test","test")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from password_reset_challenge where user_id=?",Integer.class,user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_verification_challenge where user_id=?",Integer.class,user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email=?",Integer.class,email)).isZero();
        assertThatThrownBy(()->auth.refresh(session.refreshToken(),"test","test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->actions.consume(user.getId(),"DELETE_ACCOUNT",proof)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->flows.consume("logout-"+user.getId(),browser,user.getId())).isInstanceOf(ApiException.class);
    }
}
