package com.szpu.algohub.common.logging;

import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

import ch.qos.logback.classic.spi.IThrowableProxy;

import com.szpu.algohub.common.Redaction;

/** 异常堆栈（%wEx）输出前去掉凭据：异常消息里可能带着请求头或带令牌的地址。 */
public class RedactingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return Redaction.redact(super.throwableProxyToString(tp));
    }
}
