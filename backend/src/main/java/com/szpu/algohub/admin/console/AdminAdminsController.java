package com.szpu.algohub.admin.console;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;

/**
 * 管理员与权限：列出管理员，把学生设为管理员、调整权限、取消管理员。
 * permissions 为 null 表示全部权限。不能改自己；系统里至少保留一个状态正常、能管理管理员的账号。
 * 设为管理员时清掉以前留下的密码：管理员入口只认密码，新管理员要先用登录邮箱验证后重新设置。
 */
@RestController
@RequestMapping("/api/admin/admins")
public class AdminAdminsController {

    /** role：ADMIN 或 STUDENT；permissions：null = 全部权限，否则权限代码列表 */
    public record ChangeRequest(String role, List<String> permissions) {
    }

    private final NamedParameterJdbcTemplate db;
    private final AdminAccess access;
    private final AdminUsers users;
    private final AuthService auth;

    public AdminAdminsController(NamedParameterJdbcTemplate db, AdminAccess access, AdminUsers users, AuthService auth) {
        this.db = db;
        this.access = access;
        this.users = users;
        this.auth = auth;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        List<Map<String, Object>> rows = db.queryForList("""
                select u.id, u.username, u.display_name as "displayName",
                       coalesce((select e.email from verified_login_email e where e.user_id = u.id
                                  order by e.verified_at desc limit 1), u.email) as email,
                       u.status, u.admin_permissions is null as "full", u.admin_permissions as permissions,
                       u.password_hash is not null as "hasPassword", u.last_login_at as "lastLoginAt",
                       u.last_seen_at as "lastSeenAt", u.created_at as "createdAt"
                  from app_user u
                 where u.role = 'ADMIN'
                 order by (u.admin_permissions is null) desc, u.id
                """, Map.of());
        return ApiResult.ok(rows.stream().map(row -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
            boolean full = Boolean.TRUE.equals(copy.get("full"));
            copy.put("permissions", full ? List.of(AdminPermission.values()).stream().map(Enum::name).toList()
                    : AdminAccess.json(copy.get("permissions")));
            return copy;
        }).toList());
    }

    @PostMapping("/{userId}")
    @Transactional
    public ApiResult<Map<String, Object>> change(@PathVariable long userId, @RequestBody ChangeRequest request,
                                                 HttpServletRequest http) {
        String role = request == null ? null : request.role();
        if (!"ADMIN".equals(role) && !"STUDENT".equals(role)) {
            throw ApiException.validation("角色只能是 ADMIN 或 STUDENT");
        }
        String[] permissions = null;
        if ("ADMIN".equals(role) && request.permissions() != null) {
            for (String code : request.permissions()) {
                if (AdminPermission.parse(code).isEmpty()) {
                    throw ApiException.validation("不认识的权限：" + code);
                }
            }
            Set<AdminPermission> parsed = AdminAccess.parse(request.permissions());
            if (parsed.isEmpty()) {
                throw ApiException.validation("至少选一项权限；要取消管理员请把角色改成 STUDENT");
            }
            permissions = parsed.stream().map(Enum::name).toArray(String[]::new);
        }
        if (userId == CurrentUser.requireId()) {
            throw ApiException.conflict("不能修改自己的角色或权限");
        }
        users.lockAdmins();
        Map<String, Object> target = users.row(userId);
        AdminAccess.Grant before = access.of(userId);
        boolean staysKeeper = "ADMIN".equals(role) && (permissions == null || List.of(permissions).contains("ADMINS"));
        if (before.keeper() && !staysKeeper && !access.anotherKeeper(userId)) {
            throw ApiException.conflict("至少要保留一个拥有「管理员与权限」的管理员");
        }
        if ("ADMIN".equals(role)) {
            if (!"ACTIVE".equals(target.get("status"))) {
                throw ApiException.conflict("这个账号已停用，先启用再设为管理员");
            }
            MapSqlParameterSource p = new MapSqlParameterSource("id", userId)
                    .addValue("permissions", permissions == null ? null : String.join(",", permissions));
            // 学生第一次设为管理员：清掉以前留下的密码，要用登录邮箱验证后重新设置
            db.update("""
                    update app_user
                       set password_hash = case when role = 'ADMIN' then password_hash else null end,
                           role = 'ADMIN',
                           admin_permissions = case when cast(:permissions as text) is null then null
                                                    else string_to_array(cast(:permissions as text), ',')::varchar(32)[] end,
                           updated_at = now()
                     where id = :id
                    """, p);
        } else if (before.admin()) {
            db.update("update app_user set role = 'STUDENT', admin_permissions = null, updated_at = now() where id = :id",
                    Map.of("id", userId));
            // 管理员的登录状态作废：以后用学生的方式（邮箱验证码或 GitHub）登录
            auth.logoutAll(userId, http.getRemoteAddr(), http.getHeader("User-Agent"));
        }
        return ApiResult.ok(users.row(userId));
    }
}
