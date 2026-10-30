package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * 走真实过滤器链的接口测试：验证“整站需要登录”、Bearer 鉴权、
 * 刷新令牌 Cookie 的属性，以及登出后旧访问令牌立即失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;

    private final ObjectMapper mapper = new ObjectMapper();
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'api-test%'");
        redis.keys("algohub:*").forEach(redis::delete);

        username = "api-test-" + System.nanoTime();
        jdbcTemplate.update("insert into app_user(username,display_name,role,status) values(?,'test','STUDENT','ACTIVE')", username);
        jdbcTemplate.update("insert into verified_login_email(user_id,email) select id,? from app_user where username=?", username+"@example.test",username);
    }

    private record Session(String accessToken, Cookie refreshCookie) {
    }

    private MvcResult emailLogin(String email) throws Exception {
        jdbcTemplate.update("insert into email_login_challenge(email,code_hash,expires_at) values(?,?,now()+interval '10 minutes') on conflict(email) do update set code_hash=excluded.code_hash,expires_at=excluded.expires_at,attempts=0",
                email,EmailLoginService.hash("LOGIN:"+email+":123456"));
        return mockMvc.perform(post("/api/auth/email/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("email",email,"code","123456")))).andReturn();
    }

    private Session login() throws Exception {
        MvcResult result=emailLogin(username+"@example.test");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body=mapper.readTree(result.getResponse().getContentAsString());
        return new Session(body.path("data").path("accessToken").asText(),result.getResponse().getCookie(AuthController.REFRESH_COOKIE));
    }

    @Test
    void tokenReturnedByEmailRegistrationWorksImmediately() throws Exception {
        MvcResult registered=emailLogin(username+"-new@example.test");
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        String token=mapper.readTree(registered.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        assertThat(mockMvc.perform(get("/api/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void protectedEndpointsRequireLogin() throws Exception {
        for (String url : new String[]{"/api/stats/profile", "/api/resources", "/api/contests",
                "/api/accounts", "/api/me"}) {
            MvcResult result = mockMvc.perform(get(url)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("未登录访问 %s 应返回 401", url)
                    .isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
        }
    }

    @Test
    void meReturnsNullDataForGuestInsteadOfError() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
    }

    @Test
    void refreshCookieIsHttpOnlyAndScopedToAuthPath() throws Exception {
        Session session = login();
        assertThat(session.refreshCookie()).isNotNull();
        assertThat(session.refreshCookie().isHttpOnly()).isTrue();
        assertThat(session.refreshCookie().getPath()).isEqualTo("/api/auth");
        // 刷新令牌不能出现在响应体里
        MvcResult result = emailLogin(username+"@example.test");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(session.refreshCookie().getValue());
    }

    @Test
    void bearerTokenGrantsAccess() throws Exception {
        Session session = login();
        MvcResult result = mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains(username);
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        Session session = login();
        // 改签名中间的一个字符（末尾字符有不参与解码的位，改了可能等于没改）
        String token = session.accessToken();
        int i = token.lastIndexOf('.') + 5;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
        MvcResult result = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void accessTokenStopsWorkingAfterLogout() throws Exception {
        Session session = login();
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .cookie(session.refreshCookie()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        MvcResult afterLogout = mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();
        assertThat(afterLogout.getResponse().getStatus()).isEqualTo(401);
        assertThat(afterLogout.getResponse().getContentAsString()).contains("已登出");

        // 刷新令牌也一并失效
        MvcResult refresh = mockMvc.perform(post("/api/auth/refresh").cookie(session.refreshCookie()))
                .andReturn();
        assertThat(refresh.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithCookieReturnsNewAccessToken() throws Exception {
        Session session = login();
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").cookie(session.refreshCookie()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").path("accessToken").asText()).isNotBlank();
        assertThat(result.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNotNull();
    }

    @Test
    void adminEndpointsRejectStudents() throws Exception {
        Session session = login();
        MvcResult result = mockMvc.perform(get("/api/admin/members")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    void githubStatusIsPublicAndReportsDisabledWhenUnconfigured() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/github/status")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"enabled\":false");
    }
}
