package com.szpu.algohub.admin.console;

import java.util.List;
import java.util.Optional;

/**
 * 管理员权限。app_user.admin_permissions 为 null 表示全部权限（超级管理员），数组表示只有这些。
 * 每个权限覆盖一组 /api/admin 接口前缀，按最长前缀匹配；没有配权限的管理接口一律拒绝
 * （AdminPermissionCoverageTest 保证每个管理接口都配了）。
 */
public enum AdminPermission {
    DASHBOARD("数据概览", "查看用户数量、访问量和活跃趋势", "/api/admin/dashboard"),
    USERS("用户管理", "查看全部用户、停用或启用账号、强制下线", "/api/admin/users"),
    SCHOOLS("学校与成员", "学校与邀请码、入校审批、学生名册、年级配置",
            "/api/admin/students", "/api/admin/members", "/api/admin/schools", "/api/admin/enrollment-years"),
    CONTENT("公告与内容", "公告与资源、学习提醒、导航维护",
            "/api/admin/announcements", "/api/admin/nav", "/api/admin/reminders"),
    SYNC("同步与申诉", "同步状态、失败重试、比赛刷新、归属申诉",
            "/api/admin/sync", "/api/admin/tasks", "/api/admin/ownership-appeals", "/api/admin/contests"),
    PROBLEMS("题库与标签", "题目映射、标签分类、题单与训练活动",
            "/api/admin/problem-groups", "/api/admin/problem-lists", "/api/admin/tag-mappings", "/api/admin/activities"),
    AUDIT("操作日志", "查看管理员做过的操作", "/api/admin/audit"),
    ADMINS("管理员与权限", "设置或取消管理员、调整管理员权限", "/api/admin/admins", "/api/admin/school-admins");

    /** 所有管理员都能访问：查自己有哪些权限 */
    public static final String ME_PATH = "/api/admin/me";

    private final String label;
    private final String description;
    private final List<String> prefixes;

    AdminPermission(String label, String description, String... prefixes) {
        this.label = label;
        this.description = description;
        this.prefixes = List.of(prefixes);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public List<String> prefixes() {
        return prefixes;
    }

    /** 请求路径对应的权限：最长前缀匹配，前缀后面必须是路径结束或 /（/api/admin/users 不会匹配 /api/admin/usersx） */
    public static Optional<AdminPermission> forPath(String path) {
        AdminPermission best = null;
        int bestLength = -1;
        for (AdminPermission permission : values()) {
            for (String prefix : permission.prefixes) {
                boolean matches = path.equals(prefix) || path.startsWith(prefix + "/");
                if (matches && prefix.length() > bestLength) {
                    best = permission;
                    bestLength = prefix.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** 权限代码转枚举；不认识的返回空 */
    public static Optional<AdminPermission> parse(String code) {
        for (AdminPermission permission : values()) {
            if (permission.name().equals(code)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
