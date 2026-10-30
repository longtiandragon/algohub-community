package com.szpu.algohub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.identity.GithubOauthService;
import com.szpu.algohub.auth.identity.IdentityMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="algohub.email.from=test@example.test")
@AutoConfigureMockMvc @Transactional
class GithubLinkLoginTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired OauthBrowserFlow flows;
    @Autowired EmailLoginService emails;
    @Autowired IdentityMapper bindings;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired EmailCodeGuard guard;
    @Autowired AuthService auth;
    @Autowired AppUserMapper users;
    @Autowired TokenStore tokens;
    @MockitoSpyBean GithubOauthService github;
    @MockitoBean JavaMailSender mail;
    final ObjectMapper json=new ObjectMapper();
    long id;
    String username,email,state,githubId;
    @BeforeEach void setup() {
        MailTestSupport.stubMimeMessages(mail);
        username="link"+System.nanoTime();email=username+"@example.test";state="state-"+username;githubId="github-"+username;
        id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values(?,?,'关联测试','STUDENT','ACTIVE') returning id",Long.class,username,encoder.encode("link-password-123"));
        jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",id,email);
        doReturn(true).when(github).isEnabled();
        doReturn(java.util.Map.of("state",state,"authorizeUrl","https://github.com/login/oauth/authorize?state="+state)).when(github).authorizeUrl(id);
    }
    private Cookie pending() throws Exception {
        String nextState=state+"-"+System.nanoTime();
        doReturn(new GithubOauthService.GithubUser(githubId,"link-user","name",null,null,email)).when(github).exchange(eq("code"),eq(nextState),any());
        var response=mvc.perform(post("/api/auth/github/callback").cookie(new Cookie(OauthBrowserFlow.COOKIE,flows.create(nextState,null,"LOGIN",null)))
                .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("code","code","state",nextState))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("LINK_REQUIRED"))
                .andExpect(jsonPath("$.data.token").doesNotExist()).andReturn().getResponse();
        Cookie cookie=response.getCookie(PendingGithubLink.COOKIE);
        assertThat(cookie).isNotNull();assertThat(cookie.isHttpOnly()).isTrue();
        return cookie;
    }
    private String code(Cookie cookie) throws Exception {
        mvc.perform(post("/api/auth/github/link-code").cookie(cookie)).andExpect(status().isOk());
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        assertThat(MailTestSupport.recipients(captor.getValue())).containsExactly(email);
        assertThat(MailTestSupport.plain(captor.getValue())).contains("link-user");
        return MailTestSupport.code(captor.getValue());
    }
    private org.springframework.test.web.servlet.ResultActions submit(Cookie cookie,java.util.Map<String,String> body) throws Exception {
        return mvc.perform(post("/api/auth/github/link-login").cookie(cookie).contentType("application/json").content(json.writeValueAsString(body)));
    }
    @Test void contextShowsOnlyMaskedFixedEmail() throws Exception {
        var cookie=pending();
        var response=mvc.perform(get("/api/auth/github/link-context").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.githubLogin").value("link-user"))
                .andExpect(jsonPath("$.data.hasPassword").value(false)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(email);
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
    }
    @Test void passwordBindsTheSavedGithubToTheOriginalAccount() throws Exception {
        jdbc.update("update app_user set role='ADMIN' where id=?",id);
        var cookie=pending();
        submit(cookie,java.util.Map.of("method","password","password","link-password-123","githubId","attacker-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token.user.id").value(id));
        assertThat(bindings.findBinding("GITHUB",githubId).getUserId()).isEqualTo(id);
        assertThat(bindings.findBinding("GITHUB","attacker-id")).isNull();
        submit(cookie,java.util.Map.of("method","password","password","link-password-123")).andExpect(status().isForbidden());
    }
    @Test void emailVerificationConsumesPendingAndDoesNotAcceptOtherLoginCodes() throws Exception {
        var cookie=pending();String otp=code(cookie);
        submit(cookie,java.util.Map.of("method","email","code",otp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token.user.id").value(id));
        assertThat(bindings.findBinding("GITHUB",githubId).getUserId()).isEqualTo(id);
        submit(cookie,java.util.Map.of("method","email","code",otp)).andExpect(status().isForbidden());
    }
    @Test void requestCannotSelectAnotherEmailOrUsername() throws Exception {
        var cookie=pending();
        submit(cookie,java.util.Map.of("method","password","password","link-password-123","username",username)).andExpect(status().isBadRequest());
        submit(cookie,java.util.Map.of("method","email","email","other@example.test","code","123456")).andExpect(status().isBadRequest());
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
    }
    @Test void missingOrDifferentBrowserCookieCannotReadSendOrVerify() throws Exception {
        pending();var wrong=new Cookie(PendingGithubLink.COOKIE,java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID());
        mvc.perform(get("/api/auth/github/link-context")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/github/link-code").cookie(wrong)).andExpect(status().isForbidden());
        submit(wrong,java.util.Map.of("method","password","password","link-password-123")).andExpect(status().isForbidden());
        verifyNoInteractions(mail);
    }
    @Test void sendCooldownSurvivesNewAuthorizationAndRecipientCannotBeChanged() throws Exception {
        var cookie=pending();
        mvc.perform(post("/api/auth/github/link-code").cookie(cookie).contentType("application/json").content("{\"email\":\"other@example.test\"}"))
                .andExpect(status().isOk());
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        assertThat(MailTestSupport.recipients(captor.getValue())).containsExactly(email);
        mvc.perform(post("/api/auth/github/link-code").cookie(pending())).andExpect(status().isTooManyRequests());
        verify(mail,times(1)).send(any(MimeMessage.class));
    }
    @Test void fiveAttemptsExhaustPendingEvenWithCorrectCode() throws Exception {
        var cookie=pending();String otp=code(cookie),wrong=otp.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++) submit(cookie,java.util.Map.of("method","email","code",wrong)).andExpect(status().isBadRequest());
        submit(cookie,java.util.Map.of("method","email","code",otp)).andExpect(status().isTooManyRequests());
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
    }
    @Test void identityChangeAndEmailChangeInvalidatePending() throws Exception {
        var cookie=pending();
        jdbc.update("update app_user set tokens_valid_from=now()+interval '1 second' where id=?",id);
        submit(cookie,java.util.Map.of("method","password","password","link-password-123")).andExpect(status().isForbidden());
        var fresh=pending();
        jdbc.update("update verified_login_email set email=? where user_id=?","new-"+email,id);
        mvc.perform(post("/api/auth/github/link-code").cookie(fresh)).andExpect(status().isForbidden());
        verifyNoInteractions(mail);
    }
    @Test void accountAttemptLimitSurvivesNewPendingFlows() throws Exception {
        for(int flow=0;flow<2;flow++) {
            var cookie=pending();
            for(int attempt=0;attempt<5;attempt++)
                submit(cookie,java.util.Map.of("method","password","password","wrong-password")).andExpect(status().is4xxClientError());
        }
        submit(pending(),java.util.Map.of("method","password","password","link-password-123"))
                .andExpect(status().isTooManyRequests());
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
    }
    @Test void dailyEmailQuotaAlsoAppliesToLinkCodes() throws Exception {
        var cookie=pending();
        for(int i=0;i<EmailCodeGuard.DAILY_SENDS;i++) guard.countSend(email);
        mvc.perform(post("/api/auth/github/link-code").cookie(cookie)).andExpect(status().isTooManyRequests());
        verifyNoInteractions(mail);
    }
    @Test void expiredPendingAndDifferentLoggedInUserAreRejected() throws Exception {
        var cookie=pending();
        Long other=jdbc.queryForObject("insert into app_user(username,display_name,role,status) values(?,'其他账号','STUDENT','ACTIVE') returning id",Long.class,"other-"+username);
        var session=auth.issueSession(users.findById(other),"test","test","TEST");
        mvc.perform(get("/api/auth/github/link-context").cookie(cookie).header("Authorization","Bearer "+session.view().accessToken()))
                .andExpect(status().isForbidden());
        redis.delete("algohub:github-link:"+EmailLoginService.hash(cookie.getValue()));
        submit(cookie,java.util.Map.of("method","password","password","link-password-123")).andExpect(status().isForbidden());
    }
    @Test void ordinaryLoginCodeCannotAuthorizeGithubLinking() throws Exception {
        var cookie=pending();
        emails.send(email,"test");
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        submit(cookie,java.util.Map.of("method","email","code",MailTestSupport.code(captor.getValue()))).andExpect(status().isBadRequest());
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
    }
    @Test void rejectedBindingDoesNotCreateOrEvictDeviceSessions() throws Exception {
        jdbc.update("update app_user set role='ADMIN' where id=?",id);
        github.bind(id,new GithubOauthService.GithubUser("already-"+githubId,"original",null,null,null,null));
        var existing=auth.issueSession(users.findById(id),"existing-device","test","TEST");
        var before=tokens.listSessions(id,null);
        var cookie=pending();
        submit(cookie,java.util.Map.of("method","password","password","link-password-123"))
                .andExpect(status().isConflict());
        assertThat(tokens.listSessions(id,null)).isEqualTo(before);
        assertThat(auth.refresh(existing.refreshToken(),"existing-device","test")).isNotNull();
        assertThat(bindings.findBinding("GITHUB",githubId)).isNull();
        assertThat(bindings.findBinding("GITHUB","already-"+githubId).getUserId()).isEqualTo(id);
    }
}
