package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonthlyReportMapper {
    String ELIGIBLE = """
        with eligible as (
            -- 月报是自己的回顾：和个人档案一样算自己的全部账号，不按归属状态筛（同届名次另走校内排行，只算已验证的）
            select * from platform_account where user_id=#{userId} and platform <> 'LANQIAO'
        )
        """;

    @Select(ELIGIBLE + AccountMetricSql.DAILY + """
        select a.platform,
               max(a.last_success_at) as "lastSuccessAt",
               bool_or(a.history_complete or exists (select 1 from daily_stat x
                   where x.account_id = a.id and x.metric = 'FIRST_AC' and x.source = 'SUBMISSION')) as "firstAcAvailable",
               bool_and(a.history_complete) as "historyComplete",
               coalesce(max((select sum(x.value) from metric_daily x
                   where x.platform=a.platform and x.metric='FIRST_AC')),0) as solved,
               count(distinct d.stat_date) filter (where d.value > 0) as "activeDays"
        from platform_account a
        left join daily_stat d on d.account_id = a.id and d.source = 'SUBMISSION'
            and d.metric in ('FIRST_AC', 'SUBMISSIONS', 'AC_SUBMISSIONS', 'UNIQUE_AC')
            and d.stat_date between #{from} and #{to}
        where a.user_id = #{userId} and a.platform <> 'LANQIAO'
        group by a.platform order by solved desc, a.platform
        """)
    List<Map<String, Object>> platforms(@Param("userId") Long userId,
        @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
        select count(distinct d.stat_date) from daily_stat d
        join platform_account a on a.id = d.account_id
        where a.user_id = #{userId} and a.platform <> 'LANQIAO'
          and d.source = 'SUBMISSION' and d.value > 0
          and d.metric in ('FIRST_AC', 'SUBMISSIONS', 'AC_SUBMISSIONS', 'UNIQUE_AC')
          and d.stat_date between #{from} and #{to}
        """)
    long activeDays(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
