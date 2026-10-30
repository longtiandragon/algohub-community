package com.szpu.algohub.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

import com.szpu.algohub.common.logging.RedactingMessageConverter;

/** 日志、错误说明与导出里的凭据：只隐藏值，名字和其余文字原样保留。 */
class RedactionTest {

    @Test
    void cookieAndAuthorizationHeadersAreMaskedToTheEndOfTheLine() {
        String text = "请求失败\nCookie: LEETCODE_SESSION=abc123; csrftoken=xyz\nSet-Cookie: t=zzz; Path=/\n"
                + "Authorization: Bearer abc.def.ghi\nAccept: */*";
        String masked = Redaction.redact(text);
        assertThat(masked).doesNotContain("abc123", "xyz", "zzz", "abc.def.ghi")
                .contains("Cookie: [已隐藏]", "Set-Cookie: [已隐藏]", "Authorization: [已隐藏]", "Accept: */*", "请求失败");
    }

    @Test
    void sessionCookiesTokensAndJwtsKeepTheirNames() {
        String masked = Redaction.redact("UOJSESSID=s3cr3t; __client_id=c1; _uid=42; cf_clearance=cf "
                + "https://example.com/cb?access_token=tok&state=1 "
                + "{\"cookie\":\"a=b; c=d\",\"refreshToken\":\"r1\"} "
                + "jwt eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIyIiwiZXhwIjoxfQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ "
                + "t=nowcoder-t-cookie");
        assertThat(masked).doesNotContain("s3cr3t", "c1;", "=42", "=cf ", "tok&", "a=b", "r1", "eyJ", "nowcoder-t-cookie")
                .contains("UOJSESSID=[已隐藏]", "__client_id=[已隐藏]", "access_token=[已隐藏]", "state=1",
                        "\"refreshToken\":\"[已隐藏]", "t=[已隐藏]");
    }

    @Test
    void ordinaryMessagesAreUntouched() {
        for (String text : new String[] {
                "洛谷拒绝本次请求（403），可能为防护或访问限制",
                "Codeforces 源站返回 502",
                "list?start=0&limit=30&user=sample_user",
                "平台返回的 UID 与申请不一致（账号可能已改名或注销），请重新申请",
                "把验证串 AlgoHub-abcdefghijklmnop 写进个人简介"}) {
            assertThat(Redaction.redact(text)).isEqualTo(text);
        }
        assertThat(Redaction.redact(null)).isNull();
    }

    @Test
    void logMessagesGoThroughTheRedactingConverter() {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent("test", context.getLogger("test"), Level.WARN,
                "保存登录态失败 Cookie: {}", null, new Object[] {"UOJSESSID=very-secret"});
        RedactingMessageConverter converter = new RedactingMessageConverter();
        converter.setContext(context);
        converter.start();
        assertThat(converter.convert(event)).isEqualTo("保存登录态失败 Cookie: [已隐藏]");
    }
}
