package com.szpu.algohub.school;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schools/{school}/students")
public class StudentDirectoryController {
    private final NamedParameterJdbcTemplate db;
    private final CohortLeaderAccess access;
    private final CohortLeaderMapper leaders;
    private static final String MEMBERS = """
        from school_member m join app_user u on u.id=m.user_id
        where m.school_id=:school and m.status='APPROVED' and u.status='ACTIVE' and u.role='STUDENT'
        """;

    public StudentDirectoryController(NamedParameterJdbcTemplate db,CohortLeaderAccess access,CohortLeaderMapper leaders) {
        this.db=db;
        this.access=access;
        this.leaders=leaders;
    }

    private void requireDirectory(long actor,long school,boolean schoolManagersOnly) {
        if (!schoolManagersOnly) {
            for (var scope:leaders.scopes(actor)) {
                if (((Number)scope.get("schoolId")).longValue()==school) {
                    access.requireScope(actor,school,((Number)scope.get("enrollmentYear")).intValue());
                    return;
                }
            }
        }
        access.requireManager(actor,school);
    }

    @Transactional
    @GetMapping("/directory")
    public ApiResult<?> directory(@PathVariable long school,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="30") int size,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) Integer year,@RequestParam(required=false) String className,
        @RequestParam(required=false) List<Long> ids,@RequestParam(defaultValue="") String restriction) {
        long actor=CurrentUser.requireId();
        boolean leader="LEADER".equals(restriction);
        requireDirectory(actor,school,leader);
        if (page<1 || size<1 || size>100 || (ids!=null && ids.size()>100) || (!restriction.isEmpty()&&!leader))
            throw ApiException.validation("无效的学生名单筛选条件");
        var args=new HashMap<String,Object>(Map.of("school",school,"owner",actor,"size",size,"offset",((long)page-1)*size));
        String base=MEMBERS;
        if (leader) base+=" and m.enrollment_year is not null and not exists(select 1 from school_administrator sa where sa.user_id=u.id)";
        var groups=db.queryForList("select m.enrollment_year as year,coalesce(m.class_name,'') as \"className\",count(*) as count "
            +base+" group by m.enrollment_year,coalesce(m.class_name,'') order by 1 desc nulls last,2",args);
        String where=base.replace("where m.school_id", """
            left join school_student_remark r on r.school_id=m.school_id and r.student_id=m.user_id and r.owner_id=:owner
            where m.school_id""");
        if (year!=null) { where+=" and m.enrollment_year=:year";args.put("year",year); }
        if (className!=null) { where+=" and coalesce(m.class_name,'')=:className";args.put("className",className); }
        if (ids!=null) {
            if (ids.isEmpty()) where+=" and false";
            else { where+=" and u.id in (:ids)";args.put("ids",ids); }
        }
        if (!q.isBlank()) {
            where+=" and position(lower(:query) in lower(concat_ws(' ',u.username,u.display_name,r.remark,m.student_no,m.class_name)))>0";
            args.put("query",q.strip());
        }
        long total=db.queryForObject("select count(*) "+where,args,Long.class);
        var items=db.queryForList("""
            select u.id as "userId",u.display_name as name,u.username,coalesce(r.remark,'') as remark,
                   m.enrollment_year as year,coalesce(m.class_name,'') as "className",coalesce(m.student_no,'') as "studentNo"
            """+where+" order by m.enrollment_year desc nulls last, m.class_name nulls last,m.student_no nulls last,u.id limit :size offset :offset",args);
        return ApiResult.ok(Map.of("items",items,"groups",groups,"total",total));
    }

    @Transactional
    @GetMapping("/remarks")
    public ApiResult<Map<Long,String>> remarks(@PathVariable long school) {
        long actor=CurrentUser.requireId();
        try { requireDirectory(actor,school,false); }
        catch(ApiException ex) {
            if(ex.getKind()!=com.szpu.algohub.common.ErrorKind.FORBIDDEN) throw ex;
            return ApiResult.ok(Map.of());
        }
        Map<Long,String> result=new HashMap<>();
        db.query("select u.id,r.remark "+MEMBERS.replace("where m.school_id", """
            join school_student_remark r on r.school_id=m.school_id and r.student_id=m.user_id and r.owner_id=:owner
            where m.school_id"""),Map.of("school",school,"owner",actor),
            (org.springframework.jdbc.core.RowCallbackHandler)rs->result.put(rs.getLong(1),rs.getString(2)));
        return ApiResult.ok(result);
    }

    public record Update(String remark) {}

    @Transactional
    @PutMapping("/{student}/remark")
    public ApiResult<Boolean> update(@PathVariable long school,@PathVariable long student,@RequestBody Update input) {
        long actor=CurrentUser.requireId();
        requireDirectory(actor,school,false);
        if(input==null || input.remark()==null) throw ApiException.validation("请输入备注名");
        String value=input.remark().strip();
        if(value.codePointCount(0,value.length())>64 || value.codePoints().anyMatch(Character::isISOControl))
            throw ApiException.validation("备注名最多 64 个字符，仅支持单行文本");
        var args=Map.of("school",school,"student",student,"owner",actor,"value",value);
        if(db.queryForList("select u.id "+MEMBERS+" and u.id=:student for share of m,u",args,Long.class).isEmpty())
            throw ApiException.notFound("未找到本校学生");
        if(value.isEmpty()) db.update("delete from school_student_remark where school_id=:school and student_id=:student and owner_id=:owner",args);
        else db.update("""
            insert into school_student_remark(school_id,owner_id,student_id,remark) values (:school,:owner,:student,:value)
            on conflict(school_id,owner_id,student_id) do update set remark=excluded.remark
            """,args);
        return ApiResult.ok(true);
    }
}
