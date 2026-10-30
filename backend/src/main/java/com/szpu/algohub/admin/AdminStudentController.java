package com.szpu.algohub.admin;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.ApiException;

/** 管理统计不套用公开榜单的隐私过滤；只允许 ADMIN 访问。每名学生一行。 */
@RestController
@RequestMapping("/api/admin/students")
public class AdminStudentController {
    private final NamedParameterJdbcTemplate db;
    public AdminStudentController(NamedParameterJdbcTemplate db) { this.db=db; }
    private static final String BASE = """
        with students as (
          select u.id, u.username, u.display_name as "displayName", u.status as "accountStatus",
            coalesce(ve.email, u.email) as "email",
            m.applied_at as "appliedAt", m.reviewed_at as "reviewedAt",
            u.created_at as "createdAt", m.id as "membershipId", m.school_id as "schoolId",
            sc.name as "schoolName", coalesce(m.status,'NONE') as "membershipStatus",
            m.student_no as "studentNo", m.enrollment_year as "enrollmentYear", m.class_name as "className",
            exists(select 1 from verified_login_email e where e.user_id=u.id) as "emailVerified",
            (select count(*) from platform_account a where a.user_id=u.id and a.ownership_status='VERIFIED'
                and a.platform <> 'LANQIAO') as "verifiedCount"
          from app_user u
          left join lateral (
            select sm.* from school_member sm where sm.user_id=u.id
              and (:schoolId=0 or sm.school_id=:schoolId)
            order by case sm.status when 'APPROVED' then 0 when 'PENDING' then 1 else 2 end, sm.id desc limit 1
          ) m on true
          left join school sc on sc.id=m.school_id
          left join verified_login_email ve on ve.user_id=u.id
          where u.role='STUDENT' and (:schoolId=0 or m.school_id=:schoolId)
            and (:unaffiliated=false or not exists(select 1 from school_member sm where sm.user_id=u.id and sm.status='APPROVED'))
            and (:q='' or position(lower(:q) in lower(u.username||' '||u.display_name||' '||coalesce(m.student_no,'')||' '||coalesce(ve.email,u.email,'')))>0)
            and (:year=0 or m.enrollment_year=:year)
            and (:className='' or coalesce(m.class_name,'')=:className)
            and (:membership='' or coalesce(m.status,'NONE')=:membership)
        )
        """;
    @GetMapping
    public ApiResult<Map<String,Object>> list(@RequestParam(defaultValue="0") long schoolId,
        @RequestParam(defaultValue="false") boolean unaffiliated,
        @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int year,
        @RequestParam(defaultValue="") String className, @RequestParam(defaultValue="") String membership,
        @RequestParam(defaultValue="") String verified,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        if (page<1 || page>1000000 || size<1 || size>100 || q.length()>128 || className.length()>64 || schoolId<0 ||
            !List.of("","APPROVED","PENDING","REJECTED","NONE").contains(membership) ||
            !List.of("","YES","NO").contains(verified)) throw ApiException.validation("筛选或分页参数不正确");
        Map<String,Object> p=new HashMap<>();
        p.put("schoolId",schoolId); p.put("unaffiliated",unaffiliated); p.put("q",q.trim());
        p.put("year",year); p.put("className",className.trim()); p.put("membership",membership);
        p.put("verified",verified); p.put("size",size);
        String where=" where (:verified='' or (:verified='YES')=(\"verifiedCount\">0))";
        var summary=db.queryForMap(BASE+"""
            select count(*) as total,
              count(*) filter(where "membershipStatus"='APPROVED') as approved,
              count(*) filter(where "membershipStatus"='PENDING') as pending,
              count(*) filter(where "emailVerified") as "emailVerified",
              count(*) filter(where "verifiedCount">0) as "ojVerified",
              count(*) filter(where "membershipStatus"='REJECTED') as rejected,
              count(*) filter(where "membershipStatus"='NONE') as none,
              count(*) filter(where "membershipStatus"<>'APPROVED') as unaffiliated from students
            """,p);
        Long total=db.queryForObject(BASE+"select count(*) from students"+where,p,Long.class);
        int actualPage=Math.min(page,Math.max(1,(int)((total+size-1)/size)));
        p.put("offset",(actualPage-1)*size);
        var rows=db.queryForList(BASE+"select * from students"+where+" order by id desc limit :size offset :offset",p);
        return ApiResult.ok(Map.of("rows",rows,"total",total,"page",actualPage,"size",size,"summary",summary));
    }
}
