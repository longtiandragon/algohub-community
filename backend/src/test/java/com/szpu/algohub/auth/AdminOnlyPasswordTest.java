package com.szpu.algohub.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.identity.GithubOauthService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="algohub.email.from=test@example.test")
@AutoConfigureMockMvc @Transactional
class AdminOnlyPasswordTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserMapper users;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;
    @Autowired SecurityActionService actions;
    @Autowired PasswordResetService reset;
    @Autowired GithubOauthService github;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;

    AppUser create(String role) {
        String name="policy-"+System.nanoTime();
        Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values(?,?,'test',?,'ACTIVE') returning id",
            Long.class,name,encoder.encode("original-password"),role);
        return users.findById(id);
    }
    @Test void correctStudentPasswordCannotIssueSession() throws Exception {
        var user=create("STUDENT");
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\""+user.getUsername()+"\",\"password\":\"original-password\"}"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(actions.methods(user.getId()).get("password")).isEqualTo(false);
        assertThat(auth.me(user.getId()).hasPassword()).isFalse();
    }
    @Test void adminPasswordLoginAndChangeStillWork() throws Exception {
        var user=create("ADMIN");
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\""+user.getUsername()+"\",\"password\":\"original-password\"}"))
            .andExpect(status().isOk()).andExpect(header().exists("Set-Cookie"));
        auth.changePassword(user.getId(),"original-password","new-password-123","test","test");
        assertThat(auth.authenticatePassword(user.getUsername(),"new-password-123","test","test").getId()).isEqualTo(user.getId());
        assertThat(actions.methods(user.getId()).get("password")).isEqualTo(true);
    }
    @Test void publicPasswordRegistrationIsClosed() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content("{\"username\":\"register-test\",\"password\":\"password-123\",\"displayName\":\"test\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void studentCannotChangePasswordOrUseItForIdentityProof() {
        var user=create("STUDENT");
        assertThatThrownBy(()->auth.changePassword(user.getId(),"original-password","new-password-123","test","test")).hasMessageContaining("普通用户");
        assertThatThrownBy(()->actions.verify(user.getId(),"BIND_GITHUB","original-password",null)).hasMessageContaining("密码不正确");
        assertThat(encoder.matches("original-password",users.findById(user.getId()).getPasswordHash())).isTrue();
    }
    @Test void studentResetSendsNoMailAndCannotConsumeOldChallenge() {
        var user=create("STUDENT");String email=user.getUsername()+"@example.test";
        jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",user.getId(),email);
        reset.send(email,"test");verifyNoInteractions(mail);
        jdbc.update("insert into password_reset_challenge(email,user_id,code_hash,expires_at) values(?,?,?,now()+interval '10 minutes')",email,user.getId(),EmailLoginService.hash("RESET:"+email+":123456"));
        assertThatThrownBy(()->reset.reset(email,"123456","new-password-123","test","test")).hasMessageContaining("失效");
        assertThat(encoder.matches("original-password",users.findById(user.getId()).getPasswordHash())).isTrue();
    }
    @Test void historicStudentPasswordCannotServeAsFallbackWhenUnbindingGithub() {
        var user=create("STUDENT");
        assertThatThrownBy(()->github.unbind(user.getId(),"test","test")).hasMessageContaining("请先验证登录邮箱");
    }
}
