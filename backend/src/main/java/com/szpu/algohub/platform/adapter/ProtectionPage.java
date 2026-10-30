package com.szpu.algohub.platform.adapter;

import java.util.regex.Pattern;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;

/**
 * 防护 / 人机校验页。源站暂时拦下了请求：稍后重试即可，不是接口结构变化，更不能当成零题或登录失效。
 * 只在正常解析已经失败时才拿来判断，正常页面里偶然出现的字样不影响结果。
 */
final class ProtectionPage {

    /**
     * 只认拦截页才有的标记。注意：Cloudflare 会给正常页面也插一段 /cdn-cgi/challenge-platform/scripts/jsd/
     * 的检测脚本（QOJ 登录页实测如此），所以不能拿 challenge-platform 字样本身当判据。
     */
    private static final Pattern SIGNS = Pattern.compile("(?is)(<title>\\s*just a moment|_cf_chl_opt|cf-chl-"
            + "|challenge-platform/h/|cf-turnstile|checking your browser|attention required!? \\| cloudflare"
            + "|人机验证|安全验证|访问验证|滑动验证)");

    private ProtectionPage() {
    }

    static boolean looksLike(String body) {
        return body != null && SIGNS.matcher(body).find();
    }

    static ApiException error(Platform platform) {
        return new ApiException(ErrorKind.RATE_LIMITED,
                platform.getDisplayName() + " 返回防护验证页，稍后重试；这不代表登录态失效，也不是零题");
    }

    /** 解析失败时：像防护页就按限流退避，否则保留原来的解析错误。 */
    static ApiException orElse(Platform platform, String body, ApiException parseError) {
        return looksLike(body) ? error(platform) : parseError;
    }
}
