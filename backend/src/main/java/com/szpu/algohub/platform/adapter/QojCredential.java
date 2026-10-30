package com.szpu.algohub.platform.adapter;

import com.szpu.algohub.platform.CredentialPayload;
import com.szpu.algohub.common.ApiException;

/** Accept the same bare UOJSESSID value supported by OJ Insight. */
final class QojCredential {
    static String normalize(String raw) {
        var payload = CredentialPayload.parse(raw);
        String cookie = payload.cookie();
        if (cookie == null || cookie.isBlank()) return raw;
        cookie = cookie.trim();
        if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) cookie = cookie.substring(7).trim();
        if (cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0)
            throw ApiException.validation("QOJ Cookie 应为单行内容");
        if (!cookie.contains("=")) {
            if (!cookie.matches("[A-Za-z0-9,-]+"))
                throw ApiException.validation("QOJ 请填写 UOJSESSID 的值或完整 Cookie");
            cookie = "UOJSESSID=" + cookie;
        }
        return new CredentialPayload(cookie, payload.userAgent(), payload.headers()).toStorage();
    }
}
