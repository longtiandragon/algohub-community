package com.szpu.algohub.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import com.szpu.algohub.common.Redaction;

/** 日志消息（%m / %msg）输出前去掉 Cookie、令牌等凭据。在 logback-spring.xml 里替换默认转换器。 */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return Redaction.redact(super.convert(event));
    }
}
