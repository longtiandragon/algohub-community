package com.szpu.algohub.platform;

import java.net.URI;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/**
 * 出站请求白名单。每个平台只能访问自己登记的域名（含重定向后的地址），只走 HTTPS 默认端口；
 * 登录态只发给平台本身的站点，第三方数据源（如 AtCoder 的 kenkoooo）和静态资源域名一律不带。
 */
public final class OutboundPolicy {

    private record Hosts(Set<String> all, Set<String> credential) {
    }

    private static final Map<Platform, Hosts> HOSTS = new EnumMap<>(Platform.class);

    static {
        HOSTS.put(Platform.CODEFORCES, new Hosts(Set.of("codeforces.com"), Set.of("codeforces.com")));
        HOSTS.put(Platform.ATCODER, new Hosts(Set.of("atcoder.jp", "kenkoooo.com"), Set.of("atcoder.jp")));
        HOSTS.put(Platform.LUOGU, new Hosts(Set.of("www.luogu.com.cn"), Set.of("www.luogu.com.cn")));
        HOSTS.put(Platform.LEETCODE_CN, new Hosts(Set.of("leetcode.cn"), Set.of("leetcode.cn")));
        HOSTS.put(Platform.NOWCODER, new Hosts(Set.of("ac.nowcoder.com", "www.nowcoder.com", "static.nowcoder.com"),
                Set.of("ac.nowcoder.com", "www.nowcoder.com")));
        HOSTS.put(Platform.HDU, new Hosts(Set.of("acm.hdu.edu.cn"), Set.of("acm.hdu.edu.cn")));
        HOSTS.put(Platform.QOJ, new Hosts(Set.of("qoj.ac"), Set.of("qoj.ac")));
        HOSTS.put(Platform.MATIJI, new Hosts(Set.of("www.matiji.net"), Set.of("www.matiji.net")));
        HOSTS.put(Platform.LANQIAO, new Hosts(Set.of("www.lanqiao.cn"), Set.of("www.lanqiao.cn")));
    }

    private OutboundPolicy() {
    }

    /**
     * 核对地址：HTTPS、默认端口、不带用户信息、域名在该平台白名单里。不符合直接拒绝，不发请求。
     *
     * @param redirect 是否为源站返回的重定向地址（只影响提示文字）
     */
    public static URI check(Platform platform, URI uri, boolean redirect) {
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        Hosts hosts = HOSTS.get(platform);
        boolean allowed = hosts != null && host != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null
                && hosts.all().contains(host);
        if (!allowed) {
            String where = host == null ? "无效地址" : host;
            throw new ApiException(redirect ? ErrorKind.UPSTREAM_ERROR : ErrorKind.INTERNAL,
                    platform.getDisplayName() + (redirect ? " 把请求重定向到了站外地址（" : " 请求地址不在允许的域名内（")
                            + where + "），已拦截");
        }
        return uri;
    }

    /** 这个地址能不能带上登录态（Cookie、附加请求头）。 */
    public static boolean mayCarryCredential(Platform platform, URI uri) {
        Hosts hosts = HOSTS.get(platform);
        return hosts != null && uri.getHost() != null
                && hosts.credential().contains(uri.getHost().toLowerCase(Locale.ROOT));
    }
}
