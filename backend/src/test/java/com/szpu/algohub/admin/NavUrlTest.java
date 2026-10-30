package com.szpu.algohub.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;

/** 导航链接直接放进页面 href：只接受 http / https 的完整网址。 */
class NavUrlTest {

    @Test
    void acceptsHttpAndHttps() {
        assertThatCode(() -> AdminController.requireWebUrl("https://codeforces.com/problemset")).doesNotThrowAnyException();
        assertThatCode(() -> AdminController.requireWebUrl(" HTTP://oi-wiki.org/ ")).doesNotThrowAnyException();
    }

    @Test
    void rejectsScriptDataRelativeAndEmpty() {
        for (String url : new String[] {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>",
                "//evil.example/x", "/relative", "ftp://example.com", "https://", "", null, "http://exa mple.com"}) {
            assertThatThrownBy(() -> AdminController.requireWebUrl(url))
                    .as(String.valueOf(url))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("http");
        }
    }
}
