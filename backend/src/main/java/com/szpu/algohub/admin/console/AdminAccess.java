package com.szpu.algohub.admin.console;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 读管理员权限。每次管理请求都现查数据库（和 JwtAuthFilter 每次现查用户状态一样），改了权限立刻生效，不用重新登录。
 */
@Service
public class AdminAccess {

    /** 一个用户的管理权限。full = admin_permissions 为 null，拥有全部权限 */
    public record Grant(boolean admin, boolean active, boolean full, Set<AdminPermission> permissions) {

        public boolean has(AdminPermission permission) {
            return admin && permissions.contains(permission);
        }

        /** 状态正常、能管理管理员：系统里至少要留一个，免得再也没人能分配权限 */
        public boolean keeper() {
            return admin && active && has(AdminPermission.ADMINS);
        }

        /** 接口里的权限代码：按枚举顺序 */
        public List<String> codes() {
            return permissions.stream().map(Enum::name).toList();
        }
    }

    private static final Grant NONE = new Grant(false, false, false, Set.of());

    private final JdbcTemplate jdbc;

    public AdminAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Grant of(long userId) {
        List<Grant> rows = jdbc.query("select role, status, admin_permissions from app_user where id = ?",
                (rs, i) -> grant(rs.getString("role"), rs.getString("status"), rs.getArray("admin_permissions")), userId);
        return rows.isEmpty() ? NONE : rows.getFirst();
    }

    /** 除了 exceptUserId 以外，还有没有别的「状态正常、能管理管理员」的管理员 */
    public boolean anotherKeeper(long exceptUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from app_user
                               where id <> ? and role = 'ADMIN' and status = 'ACTIVE'
                                 and (admin_permissions is null or 'ADMINS' = any(admin_permissions)))
                """, Boolean.class, exceptUserId));
    }

    static Grant grant(String role, String status, Array array) throws SQLException {
        boolean active = "ACTIVE".equals(status);
        if (!"ADMIN".equals(role)) {
            return new Grant(false, active, false, Set.of());
        }
        if (array == null) {
            return new Grant(true, active, true, EnumSet.allOf(AdminPermission.class));
        }
        return new Grant(true, active, false, parse(Arrays.asList((Object[]) array.getArray())));
    }

    /** 权限代码去重、按枚举顺序；不认识的丢掉 */
    static Set<AdminPermission> parse(Collection<?> codes) {
        Set<AdminPermission> set = EnumSet.noneOf(AdminPermission.class);
        for (Object code : codes) {
            AdminPermission.parse(String.valueOf(code)).ifPresent(set::add);
        }
        return set;
    }

    /** queryForList 读出来的 varchar[] 是 JDBC 的 Array，转成 List 才能序列化成 JSON；null 保持 null（= 全部权限） */
    static Object json(Object value) {
        if (value instanceof Array array) {
            try {
                return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
            } catch (SQLException e) {
                throw new IllegalStateException("读取权限列表失败", e);
            }
        }
        return value;
    }
}
