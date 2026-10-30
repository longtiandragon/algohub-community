package com.szpu.algohub.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="algohub.email.from=algohub@example.test")
@Transactional
class EmailIdentityLifecycleTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailIdentityService identity;
    @Autowired EmailLoginService login;
    @MockitoBean JavaMailSender mail;

    @BeforeEach void stubMail() { MailTestSupport.stubMimeMessages(mail); }

    private Long account(String suffix, boolean password) {
        return jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values (?,?, '邮箱测试','STUDENT','ACTIVE') returning id",Long.class,"email-life-"+suffix,password?"test-hash":null);
    }
    private void pending(String email) {
        jdbc.update("insert into email_login_challenge(email,code_hash,expires_at) values (?,?,now()+interval '10 minutes')",email,EmailLoginService.hash("LOGIN:"+email+":123456"));
    }
    @Test void replacementInvalidatesEarlierLoginCodesAndPreservesAccount() {
        Long user=account("replace",false);
        String old="old-life@example.test", next="new-life@example.test";
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",user,old);
        pending(old); pending(next);
        identity.send(user,next);
        var captured=ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail,times(2)).send(captured.capture());
        String oldCode=MailTestSupport.code(captured.getAllValues().get(0));
        String code=MailTestSupport.code(captured.getAllValues().get(1));
        assertThatThrownBy(()->identity.verify(user,next,code)).hasMessageContaining("原邮箱验证码");
        assertThat(identity.status(user).get("email")).isEqualTo(old);
        identity.verify(user,next.toUpperCase(),code,oldCode);
        assertThat(identity.status(user).get("email")).isEqualTo(next);
        assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email in (?,?)",Integer.class,old,next)).isZero();
        assertThatThrownBy(()->login.verify(old,"123456")).hasMessageContaining("失效");
        assertThatThrownBy(()->login.verify(next,"123456")).hasMessageContaining("失效");
        assertThatThrownBy(()->identity.verify(user,next,code)).hasMessageContaining("获取验证码");
        assertThat(jdbc.queryForObject("select count(*) from school_member where user_id=?",Integer.class,user)).isZero();
    }
    @Test void mandatoryEmailCannotBeRemovedEvenWithPassword() {
        Long user=account("remove",true);
        String email="remove-life@example.test";
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",user,email);
        pending(email);
        assertThatThrownBy(()->identity.remove(user)).hasMessageContaining("不能直接移除");
        assertThat(identity.status(user).get("email")).isEqualTo(email);
    }
}
