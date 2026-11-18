package com.szpu.algohub.admin.console;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 当前管理员有哪些权限（所有管理员都能访问），前端据此隐藏没有权限的管理分组；catalog 是全部权限的说明。 */
@RestController
public class AdminMeController {

    private final AdminAccess access;

    private final JdbcTemplate db;

    public AdminMeController(AdminAccess access, JdbcTemplate db) {
        this.access = access;
        this.db = db;
    }

    @GetMapping(AdminPermission.NAVIGATION_COUNTS)
    public ApiResult<Map<String, Long>> counts() {
        var grant = access.of(CurrentUser.requireId());
        Map<String, Long> counts = new LinkedHashMap<>();
        if (grant.active() && grant.has(AdminPermission.SCHOOLS)) {
            counts.put("members", db.queryForObject("""
                    select count(*) from app_user u where u.role='STUDENT'
                    and exists(select 1 from school_member m where m.user_id=u.id and m.status='PENDING')
                    and not exists(select 1 from school_member m where m.user_id=u.id and m.status='APPROVED')
                    """, Long.class));
        }
        if (grant.active() && grant.has(AdminPermission.SYNC)) {
            counts.put("sync", db.queryForObject("select count(*) from sync_task where status='FAILED' and platform is distinct from 'LANQIAO'", Long.class));
            counts.put("appeals", db.queryForObject("select count(*) from ownership_appeal where status='PENDING'", Long.class));
        }
        return ApiResult.ok(counts);
    }

    @GetMapping(AdminPermission.ME_PATH)
    public ApiResult<Map<String, Object>> me() {
        long userId = CurrentUser.requireId();
        AdminAccess.Grant grant = access.of(userId);
        List<Map<String, String>> catalog = Arrays.stream(AdminPermission.values())
                .map(p -> Map.of("code", p.name(), "name", p.label(), "description", p.description()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("full", grant.full());
        result.put("permissions", grant.codes());
        result.put("catalog", catalog);
        return ApiResult.ok(result);
    }
}
