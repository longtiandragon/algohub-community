package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 日统计重算。原始提交保留在 submission 表，规则调整后可重算。
 * 指标口径：
 * FIRST_AC        首次通过该题的日期落在当天（需要历史覆盖足以确认）
 * UNIQUE_AC       当天通过的题目去重，可能包含以前通过过的题
 * AC_SUBMISSIONS  当天的通过提交次数
 * SUBMISSIONS     当天的全部提交次数
 * OBSERVED_DELTA  只有总数的平台，两次快照之间观察到的增量
 */
@Mapper
public interface DailyStatMapper {

    @Insert("""
            insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source)
            select user_id,id,platform,#{date},'PLATFORM_ACTIVITY',#{value},'PLATFORM_AGGREGATE'
              from platform_account where id=#{accountId}
            on conflict(account_id,stat_date,metric) do update set value=excluded.value,updated_at=now()
            """)
    int upsertActivity(@Param("accountId") Long accountId, @Param("date") LocalDate date, @Param("value") int value);

    @Delete("delete from daily_stat where account_id = #{accountId} and source = 'SUBMISSION'")
    int deleteSubmissionBased(@Param("accountId") Long accountId);

    @Delete("delete from daily_stat where account_id = #{accountId} and source = 'SNAPSHOT_DELTA'")
    int deleteSnapshotBased(@Param("accountId") Long accountId);

    @Insert("""
            insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
            select a.user_id, s.account_id, s.platform,
                   (s.submitted_at at time zone 'Asia/Shanghai')::date,
                   'SUBMISSIONS', count(*), 'SUBMISSION'
              from submission s
              join platform_account a on a.id = s.account_id
             where s.account_id = #{accountId} and (s.time_precision <> 'USER_REPORTED' or s.platform in ('LUOGU','QOJ','LEETCODE_CN'))
             group by a.user_id, s.account_id, s.platform, (s.submitted_at at time zone 'Asia/Shanghai')::date
            on conflict (account_id, stat_date, metric) do update
               set value = excluded.value, updated_at = now()
            """)
    int rebuildSubmissions(@Param("accountId") Long accountId);

    @Insert("""
            insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
            select a.user_id, s.account_id, s.platform,
                   (s.submitted_at at time zone 'Asia/Shanghai')::date,
                   'AC_SUBMISSIONS', count(*), 'SUBMISSION'
              from submission s
              join platform_account a on a.id = s.account_id
             where s.account_id = #{accountId} and (s.time_precision <> 'USER_REPORTED' or s.platform in ('LUOGU','QOJ','LEETCODE_CN')) and s.accepted = true
             group by a.user_id, s.account_id, s.platform, (s.submitted_at at time zone 'Asia/Shanghai')::date
            on conflict (account_id, stat_date, metric) do update
               set value = excluded.value, updated_at = now()
            """)
    int rebuildAcSubmissions(@Param("accountId") Long accountId);

    @Insert("""
            insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
            select a.user_id, s.account_id, s.platform,
                   (s.submitted_at at time zone 'Asia/Shanghai')::date,
                   'UNIQUE_AC', count(distinct s.problem_id), 'SUBMISSION'
              from submission s
              join platform_account a on a.id = s.account_id
             where s.account_id = #{accountId} and (s.time_precision <> 'USER_REPORTED' or s.platform in ('LUOGU','QOJ','LEETCODE_CN')) and s.accepted = true and s.problem_id is not null
             group by a.user_id, s.account_id, s.platform, (s.submitted_at at time zone 'Asia/Shanghai')::date
            on conflict (account_id, stat_date, metric) do update
               set value = excluded.value, updated_at = now()
            """)
    int rebuildUniqueAc(@Param("accountId") Long accountId);

    @Insert("""
            insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
            select u.user_id, u.account_id, u.platform,
                   (u.earliest_known_ac_at at time zone 'Asia/Shanghai')::date,
                   'FIRST_AC', count(*), 'SUBMISSION'
              from user_solved_problem u
             where u.account_id = #{accountId}
               and u.earliest_known_ac_at is not null
               and (u.first_ac_confidence = 'EXACT' or (u.platform in ('LUOGU','QOJ','LEETCODE_CN') and u.first_ac_confidence='USER_REPORTED'))
             group by u.user_id, u.account_id, u.platform, (u.earliest_known_ac_at at time zone 'Asia/Shanghai')::date
            on conflict (account_id, stat_date, metric) do update
               set value = excluded.value, updated_at = now()
            """)
    int rebuildFirstAc(@Param("accountId") Long accountId);

    /** 只有总数的平台：把相邻快照的差值记为观察增量，不伪造逐日历史。 */
    @Insert("""
            insert into daily_stat (user_id, account_id, platform, stat_date, metric, value, source)
            select a.user_id, a.id, a.platform, d.stat_date, 'OBSERVED_DELTA', d.delta, 'SNAPSHOT_DELTA'
              from platform_account a
              join (
                    select (captured_at at time zone 'Asia/Shanghai')::date as stat_date,
                           max(solved_total) - min(prev_total) as delta
                      from (
                            select captured_at, solved_total,
                                   lag(solved_total) over (order by captured_at) as prev_total
                              from total_snapshot
                             where account_id = #{accountId} and solved_total is not null
                           ) t
                     where prev_total is not null and solved_total > prev_total
                     group by (captured_at at time zone 'Asia/Shanghai')::date
                   ) d on true
             where a.id = #{accountId}
            on conflict (account_id, stat_date, metric) do update
               set value = excluded.value, updated_at = now()
            """)
    int rebuildSnapshotDelta(@Param("accountId") Long accountId);

    // Per-account aggregates cannot be summed for distinct problem metrics.
    String USER_DAILY = """
            with first_solved as (
                select platform,problem_id,min(earliest_known_ac_at) as ac_at,
                       (bool_and(first_ac_confidence='EXACT' and earliest_known_ac_at is not null)
                        or (platform in ('LUOGU','QOJ','LEETCODE_CN') and bool_or(first_ac_confidence='USER_REPORTED' and earliest_known_ac_at is not null))) as exact
                  from user_solved_problem s where s.user_id=#{userId}
                   and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                        where a.id=s.account_id and a.ownership_status='VERIFIED'))
                 group by platform,problem_id
            ), user_daily as (
                select stat_date,metric,platform,sum(value) as value
                  from daily_stat d where d.user_id=#{userId}
                   and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                        where a.id=d.account_id and a.ownership_status='VERIFIED'))
                   and metric not in ('FIRST_AC','UNIQUE_AC')
                   and stat_date between #{from} and #{to}
                 group by stat_date,metric,platform
                union all
                select (ac_at at time zone 'Asia/Shanghai')::date,'FIRST_AC',platform,count(*)
                  from first_solved where exact
                   and ac_at >= (cast(#{from} as date)::timestamp at time zone 'Asia/Shanghai')
                   and ac_at < ((cast(#{to} as date)+1)::timestamp at time zone 'Asia/Shanghai')
                 group by 1,platform
                union all
                select (s.submitted_at at time zone 'Asia/Shanghai')::date,'UNIQUE_AC',s.platform,
                       count(distinct s.problem_id)
                  from submission s join platform_account a on a.id=s.account_id
                 where a.user_id=#{userId} and s.accepted=true and s.problem_id is not null
                   and (s.time_precision <> 'USER_REPORTED' or s.platform in ('LUOGU','QOJ','LEETCODE_CN'))
                   and (#{verifiedOnly}=false or a.ownership_status='VERIFIED')
                   and s.submitted_at >= (cast(#{from} as date)::timestamp at time zone 'Asia/Shanghai')
                   and s.submitted_at < ((cast(#{to} as date)+1)::timestamp at time zone 'Asia/Shanghai')
                 group by 1,s.platform
            )
            """;

    @Select(USER_DAILY + """
            select stat_date as "statDate", metric, platform, sum(value) as value
              from user_daily
             where stat_date between #{from} and #{to}
               and (#{platform,jdbcType=VARCHAR} is null or platform = #{platform,jdbcType=VARCHAR})
             group by stat_date, metric, platform
             order by stat_date
            """)
    List<Map<String, Object>> heatmap(@Param("userId") Long userId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to, @Param("platform") String platform,
                                      @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> heatmap(Long userId, LocalDate from, LocalDate to, String platform) {
        return heatmap(userId, from, to, platform, false);
    }

    @Select(USER_DAILY + """
            select coalesce(sum(value), 0)
              from user_daily
             where metric = #{metric}
               and stat_date between #{from} and #{to}
            """)
    int sumMetric(@Param("userId") Long userId, @Param("metric") String metric,
                  @Param("from") LocalDate from, @Param("to") LocalDate to,
                  @Param("verifiedOnly") boolean verifiedOnly);

    default int sumMetric(Long userId, String metric, LocalDate from, LocalDate to) {
        return sumMetric(userId, metric, from, to, false);
    }
}
