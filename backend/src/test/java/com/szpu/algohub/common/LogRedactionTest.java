package com.szpu.algohub.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 应用实际使用的日志配置（logback-spring.xml）：消息与异常堆栈里的凭据都被隐藏。 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTest {

    private static final Logger log = LoggerFactory.getLogger(LogRedactionTest.class);

    @Test
    void applicationLogsHideCredentialsInMessagesAndStackTraces(CapturedOutput output) {
        log.warn("上游返回 Set-Cookie: {}", "UOJSESSID=leaked-value");
        log.error("同步失败", new IllegalStateException("request header Cookie: t=leaked-in-exception"));

        assertThat(output.getOut()).contains("Set-Cookie: [已隐藏]", "Cookie: [已隐藏]", "同步失败")
                .doesNotContain("leaked-value", "leaked-in-exception");
    }
}
