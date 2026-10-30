package com.szpu.algohub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;

class CredentialPayloadTest {

    @Test
    void plainStringIsTreatedAsCookieHeader() {
        CredentialPayload payload = CredentialPayload.parse("a=1; b=2");
        assertThat(payload.cookie()).isEqualTo("a=1; b=2");
        assertThat(payload.userAgent()).isNull();
        assertThat(payload.headers()).isEmpty();
    }

    @Test
    void jsonPayloadCarriesUserAgentAndExtraHeaders() {
        CredentialPayload payload = CredentialPayload.parse(
                "{\"cookie\":\"cf_clearance=x; uid=9\",\"userAgent\":\"Mozilla/5.0 TestBrowser\","
                        + "\"headers\":{\"x-csrf-token\":\"t-1\",\"blank\":\"\"}}");
        assertThat(payload.cookie()).isEqualTo("cf_clearance=x; uid=9");
        assertThat(payload.userAgent()).isEqualTo("Mozilla/5.0 TestBrowser");
        // 空值请求头被忽略，避免发出无意义的头
        assertThat(payload.headers()).containsExactly(Map.entry("x-csrf-token", "t-1"));
    }

    @Test
    void storageRoundTripKeepsAllFields() {
        CredentialPayload original = new CredentialPayload("k=v", "UA/1.0", Map.of("x-token", "abc"));
        CredentialPayload restored = CredentialPayload.parse(original.toStorage());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void describeNeverLeaksCredentialContent() {
        CredentialPayload payload = new CredentialPayload("secret=abc; other=def", "UA/1.0",
                Map.of("x-token", "super-secret"));
        String description = payload.describe();
        assertThat(description).doesNotContain("secret", "abc", "super-secret");
        assertThat(description).contains("cookie 项数=2").contains("自定义 UA=有").contains("附加请求头=1");
    }

    @Test
    void emptyPayloadIsDetected() {
        assertThat(CredentialPayload.parse(null).isEmpty()).isTrue();
        assertThat(CredentialPayload.parse("   ").isEmpty()).isTrue();
        assertThat(CredentialPayload.parse("{\"userAgent\":\"UA\"}").isEmpty()).isTrue();
        assertThat(CredentialPayload.parse("{\"cookie\":\"a=b\"}").isEmpty()).isFalse();
    }

    @Test
    void brokenJsonIsRejectedWithGuidance() {
        assertThatThrownBy(() -> CredentialPayload.parse("{\"cookie\": "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("可直接粘贴 Cookie 字符串");
    }
}
