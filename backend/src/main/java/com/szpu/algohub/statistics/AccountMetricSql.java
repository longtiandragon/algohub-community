package com.szpu.algohub.statistics;

/** Daily metrics for a caller-defined eligible account set. Dates are Beijing natural days. */
final class AccountMetricSql {
    private AccountMetricSql() {}

    static final String DAILY = """
        , first_solved as (
            select e.user_id,s.platform,s.problem_id,min(s.earliest_known_ac_at) as ac_at,
                   (bool_and(s.first_ac_confidence='EXACT' and s.earliest_known_ac_at is not null)
                    or (s.platform in ('LUOGU','QOJ','LEETCODE_CN') and bool_or(s.first_ac_confidence='USER_REPORTED' and s.earliest_known_ac_at is not null))) as exact
            from user_solved_problem s join eligible e on e.id=s.account_id
            group by e.user_id,s.platform,s.problem_id
        ), metric_daily as (
            select e.user_id,d.platform,d.stat_date,d.metric,sum(d.value) as value
            from daily_stat d join eligible e on e.id=d.account_id
            where d.source='SUBMISSION' and d.metric not in ('FIRST_AC','UNIQUE_AC')
              and d.stat_date between #{from} and #{to}
            group by e.user_id,d.platform,d.stat_date,d.metric
            union all
            select user_id,platform,(ac_at at time zone 'Asia/Shanghai')::date,'FIRST_AC',count(*)
            from first_solved where exact
              and ac_at >= (cast(#{from} as date)::timestamp at time zone 'Asia/Shanghai')
              and ac_at < ((cast(#{to} as date)+1)::timestamp at time zone 'Asia/Shanghai')
            group by user_id,platform,3
            union all
            select e.user_id,s.platform,(s.submitted_at at time zone 'Asia/Shanghai')::date,
                   'UNIQUE_AC',count(distinct s.problem_id)
            from submission s join eligible e on e.id=s.account_id
            where s.accepted and s.problem_id is not null and (s.time_precision <> 'USER_REPORTED' or s.platform in ('LUOGU','QOJ','LEETCODE_CN'))
              and s.submitted_at >= (cast(#{from} as date)::timestamp at time zone 'Asia/Shanghai')
              and s.submitted_at < ((cast(#{to} as date)+1)::timestamp at time zone 'Asia/Shanghai')
            group by e.user_id,s.platform,3
        )
        """;
}
