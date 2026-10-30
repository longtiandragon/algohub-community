package com.szpu.algohub.platform.adapter;

import com.szpu.algohub.platform.CredentialPayload;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QojCredentialTest {
    @Test void adapterActuallySendsNormalizedSession() {
        var http = org.mockito.Mockito.mock(com.szpu.algohub.platform.PlatformHttpClient.class);
        org.mockito.Mockito.when(http.get(org.mockito.ArgumentMatchers.eq(com.szpu.algohub.platform.Platform.QOJ),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("<title>Profile</title><ul class='uoj-ac-problems-list'><a href='/problem/42'>42</a></ul>");
        var adapter = new QojAdapter(http);
        var context = new com.szpu.algohub.platform.PlatformDtos.SyncContext(1L, "test", "test", null, "abc123", null);
        assertThat(adapter.fetchTotals(context).solvedTotal()).isEqualTo(1);
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(http).get(org.mockito.ArgumentMatchers.eq(com.szpu.algohub.platform.Platform.QOJ),
                org.mockito.ArgumentMatchers.eq("https://qoj.ac/user/profile/test"), org.mockito.ArgumentMatchers.anyMap(), sent.capture());
        assertThat(CredentialPayload.parse(sent.getValue()).cookie()).isEqualTo("UOJSESSID=abc123");
    }
    @Test void acceptsBareSessionAndCompleteHeader() {
        assertThat(CredentialPayload.parse(QojCredential.normalize(" abc-123 ")).cookie()).isEqualTo("UOJSESSID=abc-123");
        assertThat(CredentialPayload.parse(QojCredential.normalize("Cookie: UOJSESSID=abc; other=def")).cookie())
                .isEqualTo("UOJSESSID=abc; other=def");
    }
    @Test void preservesStructuredUserAgentAndHeaders() {
        var value = CredentialPayload.parse(QojCredential.normalize("{\"cookie\":\"abc123\",\"userAgent\":\"example\",\"headers\":{\"X-Test\":\"yes\"}}"));
        assertThat(value.cookie()).isEqualTo("UOJSESSID=abc123");
        assertThat(value.userAgent()).isEqualTo("example");
        assertThat(value.headers()).containsEntry("X-Test", "yes");
    }
    @Test void rejectsMultilineAndMalformedBareValuesWithoutEchoingSecret() {
        assertThatThrownBy(() -> QojCredential.normalize("abc\r\nInjected: value")).hasMessage("QOJ Cookie 应为单行内容");
        assertThatThrownBy(() -> QojCredential.normalize("abc;def")).hasMessage("QOJ 请填写 UOJSESSID 的值或完整 Cookie");
    }
}
