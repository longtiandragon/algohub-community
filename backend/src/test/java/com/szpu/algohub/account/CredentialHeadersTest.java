package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;

/** 登录态附带的请求头：最多 10 个，只允许白名单里的名字，值不能带换行。 */
class CredentialHeadersTest {

    @Test
    void allowedHeadersAreNormalized() {
        assertThat(AccountService.checkHeaders(Map.of(" X-CSRF-Token ", " abc ", "Referer", "https://leetcode.cn/")))
                .containsEntry("x-csrf-token", "abc")
                .containsEntry("referer", "https://leetcode.cn/")
                .hasSize(2);
        assertThat(AccountService.checkHeaders(null)).isEmpty();
    }

    @Test
    void otherHeadersAreRejected() {
        for (String name : new String[] {"Host", "Authorization", "Cookie", "x-forwarded-for", "user-agent", ""}) {
            assertThatThrownBy(() -> AccountService.checkHeaders(Map.of(name, "v")))
                    .as(name).isInstanceOf(ApiException.class).hasMessageContaining("只允许");
        }
    }

    @Test
    void tooManyOrControlCharactersAreRejected() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            many.put("x-csrf-token" + " ".repeat(i), "v");
        }
        assertThatThrownBy(() -> AccountService.checkHeaders(many)).hasMessageContaining("最多 10 个");
        assertThatThrownBy(() -> AccountService.checkHeaders(Map.of("origin", "https://a\r\nHost: evil")))
                .hasMessageContaining("控制字符");
    }
}
