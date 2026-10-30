package com.szpu.algohub.common;

/** 请求里的条数参数统一夹在 [1, max]：负数或 0 直接进 SQL 的 limit 会报错（500）。 */
public final class Limits {

    private Limits() {
    }

    public static int clamp(int requested, int max) {
        return Math.max(1, Math.min(requested, max));
    }
}
