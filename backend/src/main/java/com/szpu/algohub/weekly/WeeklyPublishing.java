package com.szpu.algohub.weekly;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.*;
import com.szpu.algohub.school.CohortLeaderAccess;
import com.szpu.algohub.school.CohortLeaderMapper;

@Service
class WeeklyPublishing {
    private final JdbcTemplate db;
    private final CohortLeaderMapper leaders;
    private final CohortLeaderAccess access;
    private final ObjectMapper json;

    WeeklyPublishing(JdbcTemplate db,CohortLeaderMapper leaders,CohortLeaderAccess access,ObjectMapper json) {
        this.db=db;
        this.leaders=leaders;
        this.access=access;
        this.json=json;
    }

    List<Map<String,Object>> schools(long actor) {
        var scopes = leaders.scopes(actor);
        var rows = db.queryForList("""
            select s.id,s.name,exists(select 1 from school_administrator a where a.school_id=s.id
                and a.user_id=u.id and u.role <> 'ADMIN') as manage
            from school s cross join app_user u where u.id=? and u.status='ACTIVE'
            and (exists(select 1 from school_member m where m.school_id=s.id and m.user_id=u.id and m.status='APPROVED')
              or exists(select 1 from school_administrator a where a.school_id=s.id and a.user_id=u.id and u.role <> 'ADMIN'))
            order by s.name
            """,actor);
        for (var row: rows) {
            if (scopes.stream().anyMatch(scope -> scope.get("schoolId").equals(row.get("id")))) row.put("manage",true);
        }
        return rows;
    }

    boolean managerOrMember(long actor,long school) {
        return schools(actor).stream().filter(s -> ((Number)s.get("id")).longValue()==school)
            .findFirst().map(s -> Boolean.TRUE.equals(s.get("manage")))
            .orElseThrow(() -> ApiException.notFound("找不到该校训练赛"));
    }

    void requirePublisher(long actor,long school) {
        if (!managerOrMember(actor,school)) throw ApiException.forbidden("需要本校训练赛管理权限");
    }

    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    List<Integer> years(long school) {
        int year=LocalDate.now(ZONE).getYear();
        return List.of(year,year-1,year-2,year-3);
    }

    List<Map<String,Object>> roster(long school) {
        return db.queryForList("""
            select u.id as "userId",u.display_name as name,m.enrollment_year as year,
                   coalesce(m.class_name,'') as "className",coalesce(m.student_no,'') as "studentNo"
            from school_member m join app_user u on u.id=m.user_id
            where m.school_id=? and m.status='APPROVED' and u.status='ACTIVE' and u.role='STUDENT'
            order by m.enrollment_year desc,m.class_name,m.student_no,u.id
            """,school);
    }

    Map<String,Object> preview(long school, WeeklySource.Meta meta) {
        var templates=db.queryForList("""
            select targets::text,audience from weekly_defaults where school_id=? and platform=? and category=?
            """,school,meta.platform(),meta.category());
        var last=db.queryForList("""
            select unnest(student_ids) from (select student_ids from weekly_training where school_id=?
              and platform=? and metadata->>'category'=? and audience='SELECTED' order by id desc limit 1) t
            """,Long.class,school,meta.platform(),meta.category());
        try {
            return Map.of("meta",meta,"years",years(school),"lastStudentIds",last,
                "audience",templates.isEmpty()?"ALL":templates.getFirst().get("audience"),
                "targets",templates.isEmpty()?Map.of():json.readTree((String)templates.getFirst().get("targets")));
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
    }

    private void lockPublisher(long actor,long school) {
        db.queryForList("select id from app_user where id=? for share",actor);
        requirePublisher(actor,school);
        var scope=leaders.scopes(actor).stream().filter(s->((Number)s.get("schoolId")).longValue()==school).findFirst();
        if(scope.isPresent()) access.requireScope(actor,school,((Number)scope.get().get("enrollmentYear")).intValue());
        else access.requireManager(actor,school);
    }

    private String validateRules(long school,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        var allowed=years(school);
        if(input.targets()==null || input.targets().isEmpty() || input.targets().entrySet().stream().anyMatch(e ->
           !allowed.contains(e.getKey()) || e.getValue()==null || e.getValue()<1 || e.getValue()>meta.tasks().size()))
            throw ApiException.validation("请选择有效年级，目标题数应在 1 到本场题数之间");
        String audience=Objects.requireNonNullElse(input.audience(),"ALL");
        if(!Set.of("ALL","SELECTED").contains(audience)) throw ApiException.validation("请选择有效开放范围");
        return audience;
    }

    private String encode(Object data) {
        try {return json.writeValueAsString(data);}
        catch(java.io.IOException e) {throw new IllegalStateException(e);}
    }

    @Transactional
    public void saveDefaults(long actor,long school,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        lockPublisher(actor,school);
        var audience=validateRules(school,input,meta);
        db.update("""
            insert into weekly_defaults(school_id,platform,category,targets,audience) values (?,?,?,?::jsonb,?)
            on conflict(school_id,platform,category) do update set targets=excluded.targets,audience=excluded.audience
            """,school,meta.platform(),meta.category(),encode(input.targets()),audience);
    }

    @Transactional
    public long insert(long actor,long school,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        return save(actor,school,null,input,meta);
    }

    @Transactional
    public long update(long actor,long school,long id,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        return save(actor,school,id,input,meta);
    }

    private long save(long actor,long school,Long id,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        lockPublisher(actor,school);
        String audience=validateRules(school,input,meta);
        LocalDate today=LocalDate.now(ZONE);
        var monday=TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY);
        LocalDate week=Objects.requireNonNullElse(input.weekStart(),today).with(monday);
        if(week.isBefore(today.with(monday)) || week.isAfter(today.plusMonths(6).with(monday)))
            throw ApiException.validation("训练周须在本周至未来半年内");
        String status=Objects.requireNonNullElse(input.status(),"PUBLISHED");
        if(!Set.of("DRAFT","PUBLISHED","SCHEDULED").contains(status)) throw ApiException.validation("无效发布方式");
        var publishAt=input.publishAt();
        if(status.equals("SCHEDULED") && (publishAt==null || !publishAt.toInstant().isAfter(java.time.Instant.now())))
            throw ApiException.validation("定时发布时间必须晚于现在");
        var ids=new LinkedHashSet<Long>();
        if(audience.equals("SELECTED")) {
            if(input.studentIds()==null || input.studentIds().isEmpty()) throw ApiException.validation("请选择至少一名学生");
            ids.addAll(input.studentIds());
            var eligible=new HashSet<Long>();
            for(var row:roster(school)) if(input.targets().containsKey(row.get("year"))) eligible.add(((Number)row.get("userId")).longValue());
            if(!eligible.containsAll(ids)) throw ApiException.validation("所选学生必须属于本校已启用的年级");
        }
        var release=status.equals("SCHEDULED")?publishAt:null;
        var students=ids.toArray(Long[]::new);
        try {
            if(id==null) { var inserted=db.queryForList("""
                insert into weekly_training(school_id,week_start,platform,contest_ref,metadata,targets,created_by,audience,student_ids,status,publish_at)
                values(?,?,?,?,?::jsonb,?::jsonb,?,?,?::bigint[],?,?) on conflict do nothing returning id
                """,Long.class,school,week,meta.platform(),meta.ref(),encode(meta),encode(input.targets()),actor,audience,students,status,release);
                if(inserted.isEmpty()) throw ApiException.conflict("本周已布置过这场比赛");
                return inserted.getFirst();
            }
            int count=db.update("""
                update weekly_training set week_start=?,platform=?,contest_ref=?,metadata=?::jsonb,targets=?::jsonb,
                    audience=?,student_ids=?::bigint[],status=?,publish_at=?
                where id=? and school_id=? and (status='DRAFT' or (status='SCHEDULED' and publish_at>clock_timestamp()))
                """,week,meta.platform(),meta.ref(),encode(meta),encode(input.targets()),audience,students,status,release,id,school);
            if(count==0) throw ApiException.conflict("仅能修改尚未发布的训练赛");
            return id;
        } catch(org.springframework.dao.DuplicateKeyException e) {throw ApiException.conflict("本周已布置过这场比赛");}
    }

    @Transactional
    public void cancel(long actor,long school,long id) {
        lockPublisher(actor,school);
        if(db.update("""
            delete from weekly_training where id=? and school_id=?
              and (status='DRAFT' or (status='SCHEDULED' and publish_at>clock_timestamp()))
            """,id,school)==0) throw ApiException.conflict("仅能取消尚未发布的训练赛");
    }
}