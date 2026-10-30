package com.szpu.algohub.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;

/** 请求本身写错是客户端错误：返回 4xx 与可读提示，不当作服务器故障返回 500。 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientErrorResponseTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name) values (?, 'x', 'client-error') returning id
                """, Long.class, "client-error-" + System.nanoTime());
        jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", userId, userId + "@ce.invalid");
        token = authService.issueSession(userMapper.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS")
                .view().accessToken();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/problem-lists").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[]{'{', '"', 't', '"', ':', (byte) 0xe9, '}'}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKind").value("VALIDATION"));
    }

    @Test
    void missingOrMistypedParametersAreBadRequest() throws Exception {
        mvc.perform(get("/api/binding-requests/lookup").header("Authorization", "Bearer " + token)
                        .param("platform", "CODEFORCES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少参数 handle"));
        mvc.perform(get("/api/activities/not-a-number").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/activities/").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKind").value("NOT_FOUND"));
    }
}
