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

    List<Integer> years(long school) {
        return db.queryForList("""
            select enrollment_year from study_goal_default where enabled
            union select enrollment_year from school_member where school_id=? and status='APPROVED' and enrollment_year is not null
            order by enrollment_year desc
            """,Integer.class,school);
    }

    Map<String,Object> preview(long school, WeeklySource.Meta meta) {
        var templates = db.queryForList("""
            select targets::text from weekly_training where school_id=? and platform=? and metadata->>'category'=?
            order by id desc limit 1
            """,String.class,school,meta.platform(),meta.category());
        try {
            return Map.of("meta",meta,"targets",templates.isEmpty()? Map.of():json.readTree(templates.getFirst()),"years",years(school));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public long insert(long actor,long school,WeeklyController.Assignment input,WeeklySource.Meta meta) {
        db.queryForList("select id from app_user where id=? for share",actor);
        requirePublisher(actor,school);
        var scopes=leaders.scopes(actor);
        var scope=scopes.stream().filter(s -> ((Number)s.get("schoolId")).longValue()==school).findFirst();
        if(scope.isPresent()) access.requireScope(actor,school,((Number)scope.get().get("enrollmentYear")).intValue());
        else access.requireManager(actor,school);
        var allowed=years(school);
        if(input.targets()==null || input.targets().isEmpty() || input.targets().entrySet().stream().anyMatch(e ->
           !allowed.contains(e.getKey()) || e.getValue()==null || e.getValue()<1 || e.getValue()>meta.tasks().size()))
            throw ApiException.validation("请选择有效年级，目标题数应在 1 到本场题数之间");
        LocalDate week=Optional.ofNullable(input.weekStart()).orElseGet(() -> LocalDate.now(ZoneId.of("Asia/Shanghai")))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        try {
            var ids=db.queryForList("""
                insert into weekly_training(school_id,week_start,platform,contest_ref,metadata,targets,created_by)
                values(?,?,?,?,?::jsonb,?::jsonb,?) on conflict do nothing returning id
                """,Long.class,school,week,meta.platform(),meta.ref(),json.writeValueAsString(meta),json.writeValueAsString(input.targets()),actor);
            if(ids.isEmpty()) throw ApiException.conflict("本周已布置过这场比赛");
            return ids.getFirst();
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
