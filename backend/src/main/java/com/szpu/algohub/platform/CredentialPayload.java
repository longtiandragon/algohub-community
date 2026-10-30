package com.szpu.algohub.platform;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/**
 * 登录态载荷。
 *
 * 只有 Cookie 往往不够：有的站点用 Cloudflare 之类的防护，放行凭据（如 cf_clearance）与浏览器
 * User-Agent 绑定，UA 不一致就会重新出挑战页；有的接口还需要 Cookie 之外的 CSRF Token 或自定义头。
 * 因此凭据保存为一个结构化载荷，而不是一行 Cookie 字符串。
 *
 * 兼容两种写法：
 * 1. 纯字符串：整体当作 Cookie 头；
 * 2. JSON：{"cookie":"a=b; c=d", "userAgent":"Mozilla/5.0 ...", "headers":{"x-csrf-token":"..."}}。
 */
public record CredentialPayload(String cookie, String userAgent, Map<String, String> headers) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static CredentialPayload parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new CredentialPayload(null, null, Map.of());
        }
        String value = raw.trim();
        if (!value.startsWith("{")) {
            return new CredentialPayload(value, null, Map.of());
        }
        try {
            JsonNode root = MAPPER.readTree(value);
            String cookie = text(root, "cookie");
            String userAgent = text(root, "userAgent");
            Map<String, String> headers = new LinkedHashMap<>();
            JsonNode headerNode = root.path("headers");
            if (headerNode.isObject()) {
                headerNode.fields().forEachRemaining(entry -> {
                    String headerValue = entry.getValue().asText(null);
                    if (headerValue != null && !headerValue.isBlank()) {
                        headers.put(entry.getKey(), headerValue);
                    }
                });
            }
            return new CredentialPayload(cookie, userAgent, Map.copyOf(headers));
        } catch (Exception e) {
            throw new ApiException(ErrorKind.VALIDATION,
                    "登录态内容不是合法的 JSON；可直接粘贴 Cookie 字符串，或使用 {\"cookie\":\"...\"} 格式");
        }
    }

    /** 序列化为存储格式。调用方负责加密后再入库。 */
    public String toStorage() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("cookie", cookie);
            if (userAgent != null && !userAgent.isBlank()) {
                map.put("userAgent", userAgent);
            }
            if (headers != null && !headers.isEmpty()) {
                map.put("headers", headers);
            }
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new ApiException(ErrorKind.INTERNAL, "登录态序列化失败");
        }
    }

    public boolean isEmpty() {
        return (cookie == null || cookie.isBlank())
                && (headers == null || headers.isEmpty());
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** 用于日志与错误信息的安全摘要，不包含凭据内容。 */
    public String describe() {
        int cookieItems = cookie == null || cookie.isBlank() ? 0 : cookie.split(";").length;
        return "cookie 项数=" + cookieItems
                + "，自定义 UA=" + (userAgent == null || userAgent.isBlank() ? "无" : "有")
                + "，附加请求头=" + (headers == null ? 0 : headers.size());
    }
}
