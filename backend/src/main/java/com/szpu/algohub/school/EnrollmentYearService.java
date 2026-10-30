package com.szpu.algohub.school;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.szpu.algohub.common.ApiException;

@Service
public class EnrollmentYearService {
    private final JdbcTemplate jdbc;
    public EnrollmentYearService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Setting(Integer year,Integer monthlyTarget,boolean enabled) {}
    public List<Integer> active() {
        return jdbc.queryForList("select enrollment_year from study_goal_default where enabled order by enrollment_year desc",Integer.class);
    }
    public List<Map<String,Object>> all() {
        return jdbc.queryForList("select enrollment_year as year,monthly_target as \"monthlyTarget\",enabled from study_goal_default order by enrollment_year desc");
    }
    public void validate(Integer year,Integer previous) {
        if(year!=null && !Objects.equals(year,previous) && !active().contains(year))
            throw ApiException.validation("请选择当前开放的入学年级");
    }
    public void save(Setting setting) {
        if(setting.year()==null || setting.year()<2000 || setting.year()>2100 || setting.monthlyTarget()==null
            || setting.monthlyTarget()<0 || setting.monthlyTarget()>10000) throw ApiException.validation("年级须为2000～2100，月目标须为0～10000");
        jdbc.update("""
            insert into study_goal_default(enrollment_year,monthly_target,enabled) values(?,?,?)
            on conflict(enrollment_year) do update set monthly_target=excluded.monthly_target,enabled=excluded.enabled
            """,setting.year(),setting.monthlyTarget(),setting.enabled());
    }
}
