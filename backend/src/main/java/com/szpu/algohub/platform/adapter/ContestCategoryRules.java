package com.szpu.algohub.platform.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 比赛分类规则。Codeforces 的 Contest.type 表示计分制而不是 Div，
 * 因此 Div 等分类按名称规则判断，联合场次允许多个标签，必要时由管理员人工覆盖。
 */
public final class ContestCategoryRules {

    private ContestCategoryRules() {
    }

    public static String classifyCodeforces(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (lower.contains("educational")) {
            tags.add("Educational");
        }
        if (lower.contains("div. 1") || lower.contains("div.1")) {
            tags.add("Div.1");
        }
        if (lower.contains("div. 2") || lower.contains("div.2")) {
            tags.add("Div.2");
        }
        if (lower.contains("div. 3") || lower.contains("div.3")) {
            tags.add("Div.3");
        }
        if (lower.contains("div. 4") || lower.contains("div.4")) {
            tags.add("Div.4");
        }
        if (lower.contains("global round")) {
            tags.add("Global");
        }
        if (tags.isEmpty()) {
            return "其他";
        }
        return String.join("+", tags);
    }

    public static String classifyAtCoder(String contestId, String title) {
        String id = contestId == null ? "" : contestId.toLowerCase(Locale.ROOT);
        if (id.startsWith("abc")) {
            return "ABC";
        }
        if (id.startsWith("arc")) {
            return "ARC";
        }
        if (id.startsWith("agc")) {
            return "AGC";
        }
        if (id.startsWith("ahc")) {
            return "AHC";
        }
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (lower.contains("beginner")) {
            return "ABC";
        }
        if (lower.contains("regular")) {
            return "ARC";
        }
        if (lower.contains("grand")) {
            return "AGC";
        }
        if (lower.contains("heuristic")) {
            return "AHC";
        }
        return "其他";
    }

    public static String classifyLeetCode(String title) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (lower.contains("双周赛") || lower.contains("biweekly")) {
            return "双周赛";
        }
        if (lower.contains("周赛") || lower.contains("weekly")) {
            return "周赛";
        }
        return "其他";
    }
}
