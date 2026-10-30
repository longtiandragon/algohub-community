package com.szpu.algohub.common;

/**
 * 模糊搜索关键字转义：用户输入的 %、_ 当普通字符，而不是通配符（否则搜「_」「%」会匹配全部题目，
 * 也能拼出代价很高的模式）。SQL 里配合 {@code like ... escape '\'} 使用。
 */
public final class SqlLike {

    private SqlLike() {
    }

    public static String escape(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
