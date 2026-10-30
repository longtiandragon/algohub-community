package com.szpu.algohub.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;

/** 选学校的列表：给名称和简称，不能带出邀请码（邀请码是申请加入学校用的）。 */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolListApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = "list" + Long.toString(System.nanoTime(), 36);
    private Long schoolId;
    private Long userId;

    @BeforeEach
    void setUp() {
        schoolId = jdbc.queryForObject("insert into school (name, short_name, invite_code) values (?, ?, ?) returning id",
                Long.class, "列表测试学校" + tag, "LT", tag + "-SECRET");
        userId = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name, role) values (?, 'x', ?, 'STUDENT') returning id
                """, Long.class, tag, tag);
        jdbc.update("insert into user_privacy (user_id) values (?)", userId);
        jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", userId, tag + "@list.invalid");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
        jdbc.update("delete from school where id = ?", schoolId);
    }

    @Test
    void listsNamesWithoutInviteCodes() throws Exception {
        String token = authService.issueSession(userMapper.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS")
                .view().accessToken();
        String body = mvc.perform(get("/api/schools").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode schools = json.readTree(body).get("data");
        JsonNode mine = null;
        for (JsonNode school : schools) {
            if (school.get("id").asLong() == schoolId) {
                mine = school;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("name").asText()).isEqualTo("列表测试学校" + tag);
        assertThat(mine.get("shortName").asText()).isEqualTo("LT");
        // 哪一所学校都不能带出邀请码
        assertThat(body).doesNotContain("inviteCode").doesNotContain(tag + "-SECRET");
    }
}
