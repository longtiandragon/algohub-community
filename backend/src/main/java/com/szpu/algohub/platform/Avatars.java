package com.szpu.algohub.platform;

import java.net.URI;

/**
 * 平台头像地址的清洗。头像由浏览器直接从平台的图片域名加载，这里只放行干净的 https 地址：
 * 协议相对地址（//img...）补成 https，其余协议、带用户信息或非默认端口的一律丢掉。
 *
 * 各平台头像图片的域名：userpic.codeforces.org、img.atcoder.jp、cdn.luogu.com.cn、assets.leetcode.cn、
 * images.nowcoder.com、avatars.lanqiao.cn、www.matiji.net。
 */
public final class Avatars {

    /** ResolvedIdentity.extra 里放头像地址的键名；平台没给头像地址时不放。 */
    public static final String KEY = "avatarUrl";

    static final int MAX_LENGTH = 512;

    private Avatars() {
    }

    /** 清洗后的 https 地址；不合格返回 null。 */
    public static String https(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        if (url.length() > MAX_LENGTH || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\' || c >= 0x7f) {
                return null;
            }
        }
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            return url;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 地址的路径部分（不含查询串），用来认默认头像的文件名。 */
    public static String path(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
