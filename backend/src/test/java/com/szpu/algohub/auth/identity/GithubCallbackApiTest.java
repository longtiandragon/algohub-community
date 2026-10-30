package com.szpu.algohub.auth.identity;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GitHub 回调接口：未登录发起的授权，第一次回调即开户并登录（带回 created 标记与刷新 Cookie），
 * 之后的回调登录同一个账号。向 GitHub 换令牌的那一步用桩代替，其余都走真实逻辑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GithubCallbackApiTest {
    @Autowired MockMvc mvc;
    @Autowired com.szpu.algohub.auth.OauthBrowserFlow flows;
    @MockitoSpyBean GithubOauthService github;

    @Test
    void firstCallbackRegistersAndSignsInLaterCallbacksReuseTheAccount() throws Exception {
        var githubUser = new GithubOauthService.GithubUser("880101", "api-freshman", "接口新生", null, null,
                "api.freshman@example.test");
        doReturn(githubUser).when(github).exchange(eq("code-1"), eq("state-1"), any());
        doReturn(githubUser).when(github).exchange(eq("code-2"), eq("state-2"), any());

        String body = mvc.perform(post("/api/auth/github/callback").cookie(new jakarta.servlet.http.Cookie(com.szpu.algohub.auth.OauthBrowserFlow.COOKIE, flows.create("state-1",null,"LOGIN",null))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"state\":\"state-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LOGIN"))
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.token.user.username").value("gh_api-freshman"))
                .andExpect(jsonPath("$.data.token.user.hasPassword").value(false))
                .andExpect(jsonPath("$.data.token.user.emailVerified").value(true))
                .andExpect(jsonPath("$.data.token.user.memberships").isEmpty())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("algohub_refresh="),
                        containsString("HttpOnly"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 刚建好的账号拿到的访问令牌要能马上用（不能先被判 401、靠前端刷新一次才恢复）
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("data").path("token").path("accessToken").asText();
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/github/callback").cookie(new jakarta.servlet.http.Cookie(com.szpu.algohub.auth.OauthBrowserFlow.COOKIE, flows.create("state-2",null,"LOGIN",null))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-2\",\"state\":\"state-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.token.user.username").value("gh_api-freshman"));
    }
}
