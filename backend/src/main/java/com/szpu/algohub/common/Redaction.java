package com.szpu.algohub.common;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从要写进日志、数据库错误说明或导出文件的文字里去掉凭据：Cookie / Set-Cookie / Authorization 整行、
 * 常见会话 Cookie 与令牌参数的值、JWT、Bearer 令牌。只替换值，保留名字，方便看出是哪一项被隐藏了。
 */
public final class Redaction {

    public static final String MASK = "[已隐藏]";

    /** Cookie 与认证头：冒号后面一直到行尾（或 JSON 字符串结束）都是凭据。 */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b((?:set-)?cookie|authorization|proxy-authorization|x-csrf-?token|x-xsrf-token)"
                    + "(\"?\\s*[:=]\\s*\"?)[^\"\\r\\n]*");

    /** 会话 Cookie、令牌类参数：只隐藏值。 */
    private static final Pattern NAMED_VALUE = Pattern.compile(
            "(?i)\\b(LEETCODE_SESSION|csrftoken|__client_id|_uid|UOJSESSID|cf_clearance|JSESSIONID|SESSION|"
                    + "PHPSESSID|NOWCODERUID|NOWCODERCLINETID|refresh_token|access_token|accessToken|refreshToken|"
                    + "id_token|token|api_key|apikey|secret|client_secret|password|passwd)"
                    + "(\"?\\s*[=:]\\s*\"?)([^;&\\s\",}]+)");

    /** 牛客的登录 Cookie 就叫 t：只认 t=，前面不能紧挨字母数字（不误伤 start=0 之类）。 */
    private static final Pattern NOWCODER_T = Pattern.compile("(?<![A-Za-z0-9_.-])(t)(=)([^;&\\s\",}]+)");

    /** JWT：三段 base64url，第一段以 eyJ 开头。 */
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    /** Bearer 令牌。 */
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");

    private static final List<Pattern> ORDER = List.of(HEADER, JWT, BEARER, NAMED_VALUE, NOWCODER_T);

    private Redaction() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : ORDER) {
            Matcher matcher = pattern.matcher(result);
            if (!matcher.find()) {
                continue;
            }
            matcher.reset();
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String replacement;
                if (pattern == JWT) {
                    replacement = MASK;
                } else if (pattern == BEARER) {
                    replacement = matcher.group(1) + MASK;
                } else {
                    replacement = matcher.group(1) + matcher.group(2) + MASK;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return result;
    }
}
