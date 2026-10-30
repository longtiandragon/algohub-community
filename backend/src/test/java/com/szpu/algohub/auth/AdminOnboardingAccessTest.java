package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 管理员不是学生：无学校、邮箱或 OJ 账号也能管理，普通用户与敏感操作仍保留校验。 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOnboardingAccessTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService auth;
    @Autowired private AppUserMapper users;

    private final List<Long> createdUsers = new ArrayList<>();

    private String session(String role, boolean verifiedEmail) {
        String name = "onboard" + Long.toString(System.nanoTime(), 36);
        Long id = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name, role)
                values (?, 'x', ?, ?) returning id
                """, Long.class, name, name, role);
        createdUsers.add(id);
        jdbc.update("insert into user_privacy (user_id) values (?)", id);
        if (verifiedEmail) {
            jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", id, name + "@test.invalid");
        }
        return "Bearer " + auth.issueSession(users.findById(id), "127.0.0.1", "JUnit", "LOGIN_SUCCESS")
                .view().accessToken();
    }

    @AfterEach void cleanup() {
        for (Long id : createdUsers) jdbc.update("delete from app_user where id = ?", id);
    }

    @Test void adminCanManageWithoutStudentOnboardingOrEmail() throws Exception {
        String token = session("ADMIN", false);
        mvc.perform(get("/api/admin/schools").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/members").header("Authorization", token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from school_member where user_id = ?", Integer.class,
                createdUsers.getFirst())).isZero();
    }

    @Test void verifiedStudentCannotReadAdminInvitesOrMembers() throws Exception {
        String token = session("STUDENT", true);
        mvc.perform(get("/api/admin/schools").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/members").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test void studentStillNeedsVerifiedEmail() throws Exception {
        String token = session("STUDENT", false);
        var response = mvc.perform(get("/api/schools").header("Authorization", token))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("请先绑定并验证登录邮箱");
    }

    @Test void adminCannotDeleteAccountWithoutActionProof() throws Exception {
        String token = session("ADMIN", false);
        Long id = createdUsers.getFirst();
        var response = mvc.perform(post("/api/me/delete").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmUsername\":\"" + users.findById(id).getUsername() + "\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isBetween(400, 499);
        assertThat(users.findById(id)).isNotNull();
    }
}
