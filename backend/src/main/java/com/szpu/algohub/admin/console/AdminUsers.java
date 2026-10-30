package com.szpu.algohub.admin.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.szpu.algohub.common.ApiException;

/** 用户管理和管理员管理共用的查询：一行用户的样子、改管理员前的锁。 */
@Service
public class AdminUsers {

    /** 每个用户一行：列表、详情、改状态后的返回都用它 */
    static final String USERS = """
            with users as (
              select u.id, u.username, u.display_name as "displayName",
                     coalesce((select e.email from verified_login_email e where e.user_id = u.id
                                order by e.verified_at desc limit 1), u.email) as email,
                     exists (select 1 from verified_login_email e where e.user_id = u.id) as "emailVerified",
                     exists(select 1 from school_administrator sa where sa.user_id=u.id) as "schoolAdmin",
                     u.avatar_url as "avatarUrl", u.role, u.status, u.admin_permissions as "adminPermissions",
                     u.created_at as "createdAt", u.last_login_at as "lastLoginAt", u.last_seen_at as "lastSeenAt",
                     m.school_id as "schoolId", sc.name as "schoolName", coalesce(m.status, 'NONE') as "membershipStatus",
                     m.enrollment_year as "enrollmentYear", m.class_name as "className", m.student_no as "studentNo",
                     (select count(*) from platform_account a where a.user_id = u.id and a.platform <> 'LANQIAO') as "accountCount",
                     (select count(*) from platform_account a where a.user_id = u.id and a.platform <> 'LANQIAO'
                         and a.ownership_status = 'VERIFIED') as "verifiedAccountCount",
                     lower(u.username || ' ' || u.display_name || ' ' || coalesce(u.email, '') || ' '
                           || coalesce((select string_agg(e.email, ' ') from verified_login_email e where e.user_id = u.id), '')
                           || ' ' || coalesce(m.student_no, '')) as search_text
                from app_user u
                left join lateral (
                  select sm.* from school_member sm where sm.user_id = u.id
                   order by case sm.status when 'APPROVED' then 0 when 'PENDING' then 1 else 2 end, sm.id desc limit 1
                ) m on true
                left join school sc on sc.id = m.school_id
            )
            """;
    static final String COLUMNS = """
            select id, username, "displayName", email, "emailVerified", "avatarUrl", role, status, "adminPermissions", "schoolAdmin",
                   "createdAt", "lastLoginAt", "lastSeenAt", "schoolId", "schoolName", "membershipStatus",
                   "enrollmentYear", "className", "studentNo", "accountCount", "verifiedAccountCount"
              from users
            """;

    private final NamedParameterJdbcTemplate db;

    public AdminUsers(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> row(long id) {
        List<Map<String, Object>> rows = db.queryForList(USERS + COLUMNS + " where id = :id", Map.of("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("没有这个用户");
        }
        return json(rows.getFirst());
    }

    /** 改管理员状态、角色前锁住全部管理员行，两个管理员同时操作时「最后一个」的判断不会穿透 */
    public void lockAdmins() {
        db.queryForList("select id from app_user where role = 'ADMIN' order by id for update", Map.of());
    }

    static Map<String, Object> json(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.computeIfPresent("adminPermissions", (k, v) -> AdminAccess.json(v));
        return copy;
    }
}
