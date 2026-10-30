package com.szpu.algohub.admin.console;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.identity.IdentityMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.Redaction;
import com.szpu.algohub.common.TimeSupport;

/**
 * 用户管理：全部用户（学生和管理员）的列表与详情，停用 / 启用账号，强制下线。
 * 停用后 JwtAuthFilter 和刷新令牌都会拒绝这个账号，这里再把所有设备退出，立刻生效。
 * 不能停用自己，也不能停用最后一个能管理管理员的管理员。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    public record StatusRequest(String status, String reason) {
    }

    private final NamedParameterJdbcTemplate db;
    private final AdminUsers users;
    private final AdminAccess access;
    private final AuthService auth;
    private final IdentityMapper identities;

    public AdminUserController(NamedParameterJdbcTemplate db, AdminUsers users, AdminAccess access, AuthService auth,
                               IdentityMapper identities) {
        this.db = db;
        this.users = users;
        this.access = access;
        this.auth = auth;
        this.identities = identities;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam(defaultValue = "") String q,
                                               @RequestParam(defaultValue = "") String role,
                                               @RequestParam(defaultValue = "") String status,
                                               @RequestParam(defaultValue = "0") long schoolId,
                                               @RequestParam(defaultValue = "created") String sort,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100 || q.length() > 128 || schoolId < 0
                || !List.of("", "STUDENT", "SCHOOL_ADMIN", "ADMIN").contains(role) || !List.of("", "ACTIVE", "DISABLED").contains(status)
                || !List.of("created", "lastSeen").contains(sort)) {
            throw ApiException.validation("筛选或分页参数不正确");
        }
        Map<String, Object> p = new HashMap<>();
        p.put("q", q.trim().toLowerCase());
        p.put("role", role);
        p.put("status", status);
        p.put("schoolId", schoolId);
        String where = """
                 where (:q = '' or position(:q in search_text) > 0)
                   and (:role = '' or (:role = 'ADMIN' and role = 'ADMIN')
                        or (:role = 'STUDENT' and role = 'STUDENT' and not "schoolAdmin")
                        or (:role = 'SCHOOL_ADMIN' and role = 'STUDENT' and "schoolAdmin"))
                   and (:status = '' or status = :status)
                   and (:schoolId = 0 or "schoolId" = :schoolId)
                """;
        Long total = db.queryForObject(AdminUsers.USERS + "select count(*) from users" + where, p, Long.class);
        int actualPage = Math.min(page, Math.max(1, (int) ((total + size - 1) / size)));
        p.put("size", size);
        p.put("offset", (actualPage - 1) * size);
        String order = "lastSeen".equals(sort) ? " order by \"lastSeenAt\" desc nulls last, id desc"
                : " order by \"createdAt\" desc, id desc";
        List<Map<String, Object>> rows = db.queryForList(AdminUsers.USERS + AdminUsers.COLUMNS + where + order + " limit :size offset :offset", p)
                .stream().map(AdminUsers::json).toList();
        Map<String, Object> summary = db.queryForMap("""
                select count(*) as total,
                       count(*) filter (where role = 'STUDENT' and not exists
                           (select 1 from school_administrator sa where sa.user_id=u.id)) as students,
                       count(*) filter (where role = 'STUDENT' and exists
                           (select 1 from school_administrator sa where sa.user_id=u.id)) as "schoolAdmins",
                       count(*) filter (where role = 'ADMIN') as admins,
                       count(*) filter (where status <> 'ACTIVE') as disabled,
                       (select count(distinct user_id) from site_visit_daily where day = :today) as "activeToday"
                  from app_user u
                """, Map.of("today", TimeSupport.today()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        result.put("page", actualPage);
        result.put("size", size);
        result.put("summary", summary);
        return ApiResult.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> user = new LinkedHashMap<>(users.row(id));
        Map<String, Object> p = Map.of("id", id, "month", TimeSupport.today().minusDays(29));
        user.putAll(db.queryForMap("""
                select u.bio,
                       (u.role = 'ADMIN' and u.password_hash is not null) as "hasPassword",
                       (select b.login from user_oauth_binding b where b.user_id = u.id and lower(b.provider) = 'github'
                         order by b.linked_at desc limit 1) as "githubLogin"
                  from app_user u where u.id = :id
                """, p));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("memberships", db.queryForList("""
                select sc.name as "schoolName", m.status, m.enrollment_year as "enrollmentYear", m.class_name as "className",
                       m.student_no as "studentNo", m.applied_at as "createdAt", m.reviewed_at as "reviewedAt"
                  from school_member m join school sc on sc.id = m.school_id
                 where m.user_id = :id
                 order by m.applied_at desc
                """, p));
        result.put("accounts", db.queryForList("""
                select id, platform, handle, ownership_status as "ownershipStatus", sync_status as "syncStatus",
                       last_success_at as "lastSyncedAt"
                  from platform_account
                 where user_id = :id and platform <> 'LANQIAO'
                 order by platform, id
                """, p));
        // 登录记录的说明和 User-Agent 来自请求，展示前和导出一样去掉可能夹带的凭据
        result.put("events", identities.recentEvents(id, 20).stream().map(event -> {
            Map<String, Object> copy = new LinkedHashMap<>(event);
            copy.computeIfPresent("detail", (k, v) -> Redaction.redact(v.toString()));
            return copy;
        }).toList());
        result.put("visits", db.queryForMap("""
                select count(*) as days30, coalesce(sum(views), 0) as views30,
                       (select last_seen_at from app_user where id = :id) as "lastSeenAt"
                  from site_visit_daily where user_id = :id and day >= :month
                """, p));
        return ApiResult.ok(result);
    }

    /** 停用或启用。停用时让这个用户所有设备退出；原因写进他的登录记录，方便以后查 */
    @PostMapping("/{id}/status")
    @Transactional
    public ApiResult<Map<String, Object>> status(@PathVariable long id, @RequestBody StatusRequest request,
                                                 HttpServletRequest http) {
        String status = request == null ? null : request.status();
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.validation("状态只能是 ACTIVE 或 DISABLED");
        }
        if (reason.length() > 200) {
            throw ApiException.validation("原因最多 200 字");
        }
        long actorId = CurrentUser.requireId();
        if (id == actorId) {
            throw ApiException.conflict("不能停用或启用自己的账号");
        }
        users.lockAdmins();
        Map<String, Object> target = users.row(id);
        if ("DISABLED".equals(status) && access.of(id).keeper() && !access.anotherKeeper(id)) {
            throw ApiException.conflict("这是最后一个能管理管理员的账号，不能停用");
        }
        if (!status.equals(target.get("status"))) {
            db.update("update app_user set status = :status, updated_at = now() where id = :id",
                    Map.of("status", status, "id", id));
            String event = "DISABLED".equals(status) ? "ACCOUNT_DISABLED" : "ACCOUNT_ENABLED";
            String detail = "管理员 " + CurrentUser.require().getDisplayName() + (reason.isEmpty() ? "" : "：" + reason);
            identities.insertEvent(id, (String) target.get("username"), event, http.getRemoteAddr(),
                    http.getHeader("User-Agent"), detail.length() > 255 ? detail.substring(0, 255) : detail);
            if ("DISABLED".equals(status)) {
                auth.logoutAll(id, http.getRemoteAddr(), http.getHeader("User-Agent"));
            }
        }
        return ApiResult.ok(users.row(id));
    }

    /** 强制下线：撤销这个用户所有设备的登录状态（和「退出所有设备」同一套机制） */
    @PostMapping("/{id}/logout")
    public ApiResult<Map<String, Object>> logout(@PathVariable long id, HttpServletRequest http) {
        users.row(id);
        if (id == CurrentUser.requireId()) {
            throw ApiException.conflict("要退出自己的设备，请到「设置 → 登录设备」操作");
        }
        int revoked = auth.logoutAll(id, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ApiResult.ok(Map.of("revokedSessions", revoked));
    }
}
