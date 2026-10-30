package com.szpu.algohub.platform;

import java.util.Arrays;

import com.szpu.algohub.common.ApiException;

/**
 * 已登记的平台。是否支持同步由各适配器的能力声明决定，这里只登记标识与入口。
 */
public enum Platform {

    CODEFORCES("Codeforces", "https://codeforces.com/"),
    ATCODER("AtCoder", "https://atcoder.jp/"),
    LUOGU("洛谷", "https://www.luogu.com.cn/"),
    LEETCODE_CN("力扣（中国站）", "https://leetcode.cn/"),
    NOWCODER("牛客", "https://ac.nowcoder.com/"),
    HDU("杭电 OJ", "https://acm.hdu.edu.cn/"),
    QOJ("QOJ", "https://qoj.ac/"),
    MATIJI("码蹄集", "https://www.matiji.net/"),
    LANQIAO("蓝桥云课", "https://www.lanqiao.cn/");

    private final String displayName;
    private final String homeUrl;

    Platform(String displayName, String homeUrl) {
        this.displayName = displayName;
        this.homeUrl = homeUrl;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHomeUrl() {
        return homeUrl;
    }

    public static Platform parse(String value) {
        return Arrays.stream(values())
                .filter(p -> p.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("未知平台: " + value));
    }
}
