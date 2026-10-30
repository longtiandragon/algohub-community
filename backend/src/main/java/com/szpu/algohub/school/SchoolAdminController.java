package com.szpu.algohub.school;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.admin.console.AdminAccess;
import com.szpu.algohub.admin.console.AdminPermission;
import com.szpu.algohub.common.*;

/** 校级授权独立于平台角色；每次请求现查授权，绝不提升为全站 ADMIN。 */
@RestController
@RequestMapping("/api/school-admin")
@Transactional
public class SchoolAdminController {
    private final JdbcTemplate db;
    private final AdminAccess access;
    private final SchoolService schools;
    public SchoolAdminController(JdbcTemplate db, AdminAccess access, SchoolService schools) {
        this.db=db; this.access=access; this.schools=schools;
    }
    private void require(long schoolId, AdminPermission permission) {
        long actor=CurrentUser.requireId();
        var grant=access.of(actor);
        if (grant.active() && grant.has(permission)) return;
        // 共享行锁将授权检查与本次写入绑定；撤销授权必须等在途操作完成。
        if (!grant.active() || grant.admin() || db.queryForList("select user_id from school_administrator where user_id=? and school_id=? for share",actor,schoolId).isEmpty())
            throw ApiException.forbidden("没有这所学校的管理权限");
    }
    @GetMapping("/schools")
    public ApiResult<List<Map<String,Object>>> schools() {
        long actor=CurrentUser.requireId();
        var grant=access.of(actor);
        return ApiResult.ok(grant.active() && (grant.has(AdminPermission.SCHOOLS) || grant.has(AdminPermission.CONTENT))
            ? db.queryForList("select id,name from school order by name")
            : db.queryForList("select s.id,s.name from school s join school_administrator a on a.school_id=s.id where a.user_id=? order by s.name",actor));
    }
    @GetMapping("/{schoolId}/members")
    public ApiResult<Map<String,Object>> members(@PathVariable long schoolId,
        @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="1") int page) {
        require(schoolId,AdminPermission.SCHOOLS);
        if (!List.of("","PENDING","APPROVED","REJECTED").contains(status) || q.length()>100 || page<1 || page>100000) throw ApiException.validation("筛选参数无效");
        String where=" from school_member m join app_user u on u.id=m.user_id where m.school_id=? and (?='' or m.status=?) and (?='' or position(lower(?) in lower(u.display_name||' '||u.username||' '||coalesce(m.student_no,'')||' '||coalesce(m.class_name,'')))>0)";
        Object[] args={schoolId,status,status,q,q};
        long total=db.queryForObject("select count(*)"+where,Long.class,args);
        var parameters=new ArrayList<Object>(List.of(args)); parameters.add((page-1)*50);
        var rows=db.queryForList("select m.id,m.user_id as \"userId\",u.display_name as \"displayName\",u.username,m.status,m.enrollment_year as \"enrollmentYear\",m.class_name as \"className\",m.student_no as \"studentNo\",u.role='ADMIN' as \"platformAdmin\""+where+" order by m.id desc limit 50 offset ?",parameters.toArray());
        return ApiResult.ok(Map.of("rows",rows,"total",total));
    }
    private void member(long schoolId,long memberId) {
        require(schoolId,AdminPermission.SCHOOLS);
        var rows=db.queryForList("select u.role from school_member m join app_user u on u.id=m.user_id where m.id=? and m.school_id=? for update of u",memberId,schoolId);
        if (rows.isEmpty()) throw ApiException.notFound("本校成员不存在");
        if ("ADMIN".equals(rows.getFirst().get("role")) && !access.of(CurrentUser.requireId()).admin()) throw ApiException.forbidden("学校管理员不能修改平台管理员");
    }
    public record Review(boolean approve,@Size(max=500) String reason) {}
    @PostMapping("/{schoolId}/members/{id}/review")
    public ApiResult<SchoolMember> review(@PathVariable long schoolId,@PathVariable long id,@Valid @RequestBody Review input) {
        member(schoolId,id);
        return ApiResult.ok(schools.review(id,input.approve(),CurrentUser.requireId(),input.reason()));
    }
    @PostMapping("/{schoolId}/members/{id}/profile")
    public ApiResult<SchoolMember> profile(@PathVariable long schoolId,@PathVariable long id,@Valid @RequestBody SchoolController.ProfileRequest input) {
        member(schoolId,id);
        if(input.enrollmentYear()==null) throw ApiException.validation("请选择年级");
        return ApiResult.ok(schools.adminUpdateProfile(id,input.enrollmentYear(),input.className(),input.studentNo(),CurrentUser.requireId()));
    }
    private void requirePublisher() {
        var grant=access.of(CurrentUser.requireId());
        if (!grant.active() || !grant.has(AdminPermission.CONTENT))
            throw ApiException.forbidden("只有具备内容管理权限的平台管理员可以管理公告");
    }
    public record NoticeInput(@NotBlank @Size(max=120) String title,@NotBlank @Size(max=10000) String content,boolean published) {}
    @GetMapping("/{schoolId}/announcements")
    public ApiResult<List<Map<String,Object>>> notices(@PathVariable long schoolId) {
        requirePublisher();
        require(schoolId,AdminPermission.CONTENT);
        return ApiResult.ok(db.queryForList("select id,title,content,published from announcement where school_id=? order by updated_at desc,id desc",schoolId));
    }
    @PostMapping("/{schoolId}/announcements")
    public ApiResult<Long> create(@PathVariable long schoolId,@Valid @RequestBody NoticeInput input) {
        requirePublisher();
        require(schoolId,AdminPermission.CONTENT);
        return ApiResult.ok(db.queryForObject("insert into announcement(school_id,title,content,published,kind,published_at) values (?,?,?,?,'TEXT',case when ? then now() else null end) returning id",Long.class,schoolId,input.title().trim(),input.content().trim(),input.published(),input.published()));
    }
    @PostMapping("/{schoolId}/announcements/{id}")
    public ApiResult<Void> update(@PathVariable long schoolId,@PathVariable long id,@Valid @RequestBody NoticeInput input) {
        requirePublisher();
        require(schoolId,AdminPermission.CONTENT);
        if(db.update("update announcement set title=?,content=?,published=?,updated_at=now(),published_at=case when ? then coalesce(published_at,now()) else published_at end where id=? and school_id=?",input.title().trim(),input.content().trim(),input.published(),input.published(),id,schoolId)==0) throw ApiException.notFound("本校公告不存在");
        return ApiResult.ok();
    }
    @DeleteMapping("/{schoolId}/announcements/{id}")
    public ApiResult<Void> delete(@PathVariable long schoolId,@PathVariable long id) {
        requirePublisher();
        require(schoolId,AdminPermission.CONTENT);
        if(db.update("delete from announcement where id=? and school_id=?",id,schoolId)==0) throw ApiException.notFound("本校公告不存在");
        return ApiResult.ok();
    }
}
