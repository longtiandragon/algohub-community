package com.szpu.algohub.admin.console;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.*;

@RestController
@RequestMapping("/api/admin/school-admins")
public class SchoolAdminGrantController {
    private final JdbcTemplate db;
    public SchoolAdminGrantController(JdbcTemplate db) { this.db=db; }
    public record Grant(@NotNull @Positive Long userId,@NotNull @Positive Long schoolId) {}
    @GetMapping("/candidates")
    public ApiResult<List<Map<String,Object>>> candidates(@RequestParam String q) {
        if(q.isBlank() || q.length()>100) throw ApiException.validation("请输入用户名或昵称");
        return ApiResult.ok(db.queryForList("select id,username,display_name as \"displayName\" from app_user where role='STUDENT' and status='ACTIVE' and position(lower(?) in lower(username||' '||display_name))>0 order by id limit 30",q.trim()));
    }
    @GetMapping
    public ApiResult<List<Map<String,Object>>> list() {
        return ApiResult.ok(db.queryForList("""
            select a.user_id as "userId",a.school_id as "schoolId",u.username,
                   u.display_name as "displayName",s.name as "schoolName",u.status
            from school_administrator a join app_user u on u.id=a.user_id
            join school s on s.id=a.school_id order by s.name,u.id
            """));
    }
    @PostMapping
    @Transactional
    public ApiResult<Void> grant(@Valid @RequestBody Grant input) {
        var rows=db.queryForList("select role,status from app_user where id=?",input.userId());
        if(rows.isEmpty()) throw ApiException.notFound("用户不存在");
        if(!"STUDENT".equals(rows.getFirst().get("role")) || !"ACTIVE".equals(rows.getFirst().get("status")))
            throw ApiException.validation("请选择正常的普通用户；平台管理员已有对应管理权限");
        if(db.queryForList("select id from school where id=?",input.schoolId()).isEmpty()) throw ApiException.notFound("学校不存在");
        db.update("insert into school_administrator(user_id,school_id,granted_by) values (?,?,?) on conflict do nothing",input.userId(),input.schoolId(),CurrentUser.requireId());
        return ApiResult.ok();
    }
    @DeleteMapping("/{userId}/{schoolId}")
    @Transactional
    public ApiResult<Void> revoke(@PathVariable long userId,@PathVariable long schoolId) {
        db.update("delete from school_administrator where user_id=? and school_id=?",userId,schoolId);
        return ApiResult.ok();
    }
}
