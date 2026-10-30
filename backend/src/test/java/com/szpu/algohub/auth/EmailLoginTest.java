package com.szpu.algohub.auth;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.MimeMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="algohub.email.from=algohub@example.test")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class EmailLoginTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired EmailLoginService login;
    @Autowired EmailIdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockitoBean JavaMailSender mail;
    String email;
    @BeforeEach void setup(){email="otp-"+System.nanoTime()+"@example.test";MailTestSupport.stubMimeMessages(mail);}
    @AfterEach void cleanup(){
        jdbc.update("delete from app_user where id in(select user_id from verified_login_email where email=?)",email);
        jdbc.update("delete from email_login_challenge where email=?",email);
    }
    String send(){
        login.send(email,"test-ip-"+System.nanoTime());
        var captor=ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(captor.capture());
        return MailTestSupport.code(captor.getValue());
    }
    @Test void createsPasswordlessAccountConsumesCodeAndDoesNotAssignSchool(){
        String code=send();var user=login.verify(email.toUpperCase(),code);
        assertThat(user.getPasswordHash()).isNull();assertThat(user.getRole()).isEqualTo("STUDENT");
        assertThat(user.getDisplayName()).matches("新同学_[0-9A-F]{8}");
        assertThat(jdbc.queryForObject("select count(*) from school_member where user_id=?",Integer.class,user.getId())).isZero();
        assertThatThrownBy(()->login.verify(email,code)).hasMessageContaining("失效");
        assertThat(identity.status(user.getId()).get("email")).isEqualTo(email);
        assertThatThrownBy(()->identity.remove(user.getId())).hasMessageContaining("不能直接移除");
    }
    @Test void sharedCampusIpCanRequestForDifferentRecipientsButSameRecipientStillCoolsDown(){
        String second="second-"+email;
        try {
            login.send(email,"shared-campus-ip");
            login.send(second,"shared-campus-ip");
            verify(mail,times(2)).send(any(MimeMessage.class));
            assertThatThrownBy(()->login.send(email,"different-ip")).hasMessageContaining("60 秒");
        } finally { jdbc.update("delete from email_login_challenge where email=?",second); }
    }
    void resetCooldown(){ redis.delete("algohub:email-login:address:"+EmailLoginService.hash(email)); }
    @Test void temporaryNameIsStableAndCustomNameIsPreserved(){
        var first=login.verify(email,send());
        resetCooldown();reset(mail);MailTestSupport.stubMimeMessages(mail);
        var again=login.verify(email,send());
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getDisplayName()).isEqualTo(first.getDisplayName());
        jdbc.update("update app_user set display_name='我的昵称' where id=?",first.getId());
        resetCooldown();reset(mail);MailTestSupport.stubMimeMessages(mail);
        assertThat(login.verify(email,send()).getDisplayName()).isEqualTo("我的昵称");
    }
    @Test void resendDoesNotResetFailuresAndTenWrongGuessesLockForFifteenMinutes(){
        String code=send();String wrong=code.equals("000000")?"000001":"000000";
        for(int i=0;i<4;i++) assertThatThrownBy(()->login.verify(email,wrong)).hasMessageContaining("验证码");
        assertThat(redis.getExpire("algohub:email-code:fail:"+EmailLoginService.hash(email))).isBetween(23*3600L,24*3600L);
        // 重发：失败次数保留，不再清零
        resetCooldown();reset(mail);MailTestSupport.stubMimeMessages(mail);String fresh=send();String wrong2=fresh.equals("000000")?"000001":"000000";
        assertThat(jdbc.queryForObject("select attempts from email_login_challenge where email=?",Integer.class,email)).isEqualTo(4);
        for(int i=0;i<5;i++) assertThatThrownBy(()->login.verify(email,wrong2)).hasMessageContaining("验证码");
        // 第 10 次猜错：锁定，待验证码作废
        assertThatThrownBy(()->login.verify(email,wrong2)).hasMessageContaining("锁定 15 分钟")
            .extracting(e->((com.szpu.algohub.common.ApiException)e).getKind()).isEqualTo(com.szpu.algohub.common.ErrorKind.RATE_LIMITED);
        assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email=?",Integer.class,email)).isZero();
        // 锁定期内：正确的码也不行，发码也不行
        assertThatThrownBy(()->login.verify(email,fresh)).hasMessageContaining("锁定");
        resetCooldown();
        assertThatThrownBy(()->login.send(email,"ip")).hasMessageContaining("锁定");
        String lockKey="algohub:email-code:lock:"+EmailLoginService.hash(email);
        assertThat(redis.getExpire(lockKey)).isBetween(14*60L,15*60L);
        // 模拟短锁到期：旧码已经作废；必须重新发码，新挑战可正常登录。
        redis.delete(lockKey);
        assertThatThrownBy(()->login.verify(email,fresh)).hasMessageContaining("失效");
        reset(mail);MailTestSupport.stubMimeMessages(mail);
        String recovered=send();
        assertThat(login.verify(email,recovered).getId()).isNotNull();
    }
    @Test void perAddressDailySendLimit(){
        for(int i=0;i<EmailCodeGuard.DAILY_SENDS;i++){ resetCooldown(); login.send(email,"ip-"+i); }
        resetCooldown();
        assertThatThrownBy(()->login.send(email,"ip-x")).hasMessageContaining("今天接收验证码的次数已达上限");
        verify(mail,times(EmailCodeGuard.DAILY_SENDS)).send(any(MimeMessage.class));
    }
    @Test void expiredCodeCannotAuthenticate(){
        String code=send();jdbc.update("update email_login_challenge set expires_at=now()-interval '1 minute' where email=?",email);
        assertThatThrownBy(()->login.verify(email,code)).hasMessageContaining("失效");
    }
    @Test void repeatedSendIsLimitedAndNoPlainCodeStored(){
        String code=send();
        assertThatThrownBy(()->login.send(email,"another-ip-"+System.nanoTime())).hasMessageContaining("60 秒");
        assertThat(jdbc.queryForObject("select code_hash from email_login_challenge where email=?",String.class,email)).hasSize(64).isNotEqualTo(code);
    }
    @Test void emailEndpointIssuesSessionWithoutPassword() throws Exception {
        String code=send();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/email/login")
            .contentType("application/json").content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.user.hasPassword").value(false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.accessToken").isNotEmpty()).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly");
    }
}
