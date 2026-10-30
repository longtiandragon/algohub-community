package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 校内排行和全站总榜。先定参与账号，再截取显示条数。
 * 参与资格统一来自视图 school_board_account（已验证、正常用户、本校已通过、愿意参与展示且档案不是仅自己可见），
 * 学校由服务端按查看者的成员记录传入，这里只叠加年级、班级、平台与指标能力的筛选。
 */
@Mapper
public interface CampusRankingMapper {
    String ELIGIBLE = """
        with eligible as (
            select a.*, e.display_name, case when coalesce(privacy.show_accounts,true) then e.avatar_url else null end as user_avatar_url, e.enrollment_year, e.class_name,
                   null::varchar as school_name, t.solved_total as snapshot_solved
            from school_board_account e
            join platform_account a on a.id = e.account_id
            left join user_privacy privacy on privacy.user_id=a.user_id
            left join lateral (
                select ts.solved_total from total_snapshot ts
                where ts.account_id = a.id
                order by ts.captured_at desc, ts.id desc limit 1
            ) t on true
            where #{scope} = 'SCHOOL' and e.school_id = #{schoolId}
              and a.platform <> 'LANQIAO'
              and (#{year,jdbcType=INTEGER} is null or e.enrollment_year = #{year,jdbcType=INTEGER})
              and (#{className,jdbcType=VARCHAR} is null or e.class_name = #{className,jdbcType=VARCHAR})
              and (#{platform,jdbcType=VARCHAR} is null or a.platform = #{platform,jdbcType=VARCHAR})
              and ((#{type} = 'TOTAL' and
                    ((a.last_success_at is not null and a.history_complete) or t.solved_total is not null or
                     exists (select 1 from user_solved_problem s where s.account_id = a.id))) or
                   (#{type} != 'TOTAL' and (exists (select 1 from user_solved_problem bs where bs.account_id=a.id
                     and bs.platform in ('LUOGU','QOJ','LEETCODE_CN') and bs.first_ac_confidence='USER_REPORTED' and bs.earliest_known_ac_at is not null) or (a.last_success_at is not null and
                     ((#{metric} = 'FIRST_AC' and a.history_complete) or
                      (#{metric} != 'FIRST_AC' and a.supports_history))) or
                   exists (select 1 from daily_stat d where d.account_id = a.id
                           and d.source = 'SUBMISSION' and d.metric = #{metric}))))
            union all
            select a.*, u.display_name, case when coalesce(p.show_accounts,true) then u.avatar_url else null end as user_avatar_url,
                   coalesce(m.enrollment_year,u.enrollment_year) as enrollment_year, null::varchar as class_name, m.school_name,
                   t.solved_total as snapshot_solved
            from platform_account a
            join app_user u on u.id = a.user_id and u.status = 'ACTIVE' and u.role = 'STUDENT'
            left join user_privacy p on p.user_id = u.id
            left join lateral (
                select ts.solved_total from total_snapshot ts
                where ts.account_id = a.id
                order by ts.captured_at desc, ts.id desc limit 1
            ) t on true
            left join lateral (
                select s.name as school_name, sm.enrollment_year from school_member sm
                join school s on s.id = sm.school_id
                where sm.user_id = u.id and sm.status = 'APPROVED'
                order by sm.id limit 1
            ) m on true
            where #{scope} = 'GLOBAL' and a.ownership_status = 'VERIFIED'
              and (#{year,jdbcType=INTEGER} is null or coalesce(m.enrollment_year,u.enrollment_year) = #{year,jdbcType=INTEGER})
              and a.platform <> 'LANQIAO'
              and coalesce(p.show_on_ranking, true)
              and coalesce(p.profile_visibility, 'SCHOOL') <> 'PRIVATE'
              and (#{platform,jdbcType=VARCHAR} is null or a.platform = #{platform,jdbcType=VARCHAR})
              and ((#{type} = 'TOTAL' and
                    ((a.last_success_at is not null and a.history_complete) or t.solved_total is not null or
                     exists (select 1 from user_solved_problem s where s.account_id = a.id))) or
                   (#{type} != 'TOTAL' and (exists (select 1 from user_solved_problem bs where bs.account_id=a.id
                     and bs.platform in ('LUOGU','QOJ','LEETCODE_CN') and bs.first_ac_confidence='USER_REPORTED' and bs.earliest_known_ac_at is not null) or (a.last_success_at is not null and
                     ((#{metric} = 'FIRST_AC' and a.history_complete) or
                      (#{metric} != 'FIRST_AC' and a.supports_history))) or
                   exists (select 1 from daily_stat d where d.account_id = a.id
                           and d.source = 'SUBMISSION' and d.metric = #{metric}))))
        )
        """;

    @Select(ELIGIBLE + AccountMetricSql.DAILY + """
        , account_totals as (
            select e.id, e.user_id, e.platform, e.snapshot_solved,
                   (select count(distinct s.problem_id) from user_solved_problem s where s.account_id=e.id) as details
            from eligible e
        ), platform_totals as (
            select t.user_id, t.platform,
                   case when t.platform='LUOGU' then greatest(
                       (select count(distinct s.problem_id) from user_solved_problem s
                        join eligible x on x.id=s.account_id where x.user_id=t.user_id and x.platform='LUOGU'),
                       coalesce(max(t.snapshot_solved),0))
                   else sum(greatest(t.details,coalesce(t.snapshot_solved,0)))::bigint end as solved
            from account_totals t group by t.user_id,t.platform
        ), cumulative_totals as (
            select user_id,sum(solved)::bigint as solved from platform_totals group by user_id
        ), scores as (
            select e.user_id as "userId", e.display_name as "displayName", e.user_avatar_url as "avatarUrl",
                   e.enrollment_year as "enrollmentYear", e.class_name as "className",
                   e.school_name as "schoolName",
                   bool_or(e.ownership_status != 'VERIFIED') as "hasUnverifiedSource",
                   max(e.last_success_at) as "lastSuccessAt",
                   greatest(coalesce(mt.solved,0) -
                       (select count(distinct s.problem_id) from user_solved_problem s
                        join eligible x on x.id=s.account_id where x.user_id=e.user_id),0) as "snapshotOnlySolved",
                   case when #{type} = 'TOTAL' then
                       coalesce(mt.solved, 0)
                   else (select coalesce(sum(d.value), 0)::bigint from metric_daily d
                         where d.user_id = e.user_id and d.metric = #{metric}
                         and d.stat_date between #{from} and #{to}) end as score,
                   (select count(distinct coalesce('g:' || gm.group_id, 'p:' || s.problem_id))
                    from user_solved_problem s join eligible x on x.id = s.account_id
                    left join problem_group_member gm on gm.problem_id = s.problem_id
                    where x.user_id = e.user_id
                      and (#{type} = 'TOTAL' or x.platform <> 'LUOGU' or s.earliest_known_ac_at is not null)) as "uniqueSolved"
            from eligible e
            left join cumulative_totals mt on mt.user_id = e.user_id
            group by e.user_id, e.display_name, e.user_avatar_url, e.enrollment_year, e.class_name, e.school_name, mt.solved
        ), ranked as (
            select *, rank() over (order by score desc) as rank,
                   row_number() over (order by score desc, "userId") as position,
                   count(*) over () as "participantCount"
            from scores
        )
        select *, case when #{type} = 'TOTAL' then score end as solved
        from ranked
        where position <= #{limit}
           or abs(position - (select position from ranked where "userId" = #{userId})) <= 2
        order by position
        """)
    List<Map<String, Object>> rows(@Param("userId") Long userId, @Param("schoolId") Long schoolId,
        @Param("scope") String scope,
        @Param("type") String type, @Param("metric") String metric,
        @Param("from") LocalDate from, @Param("to") LocalDate to,
        @Param("platform") String platform, @Param("year") Integer year,
        @Param("className") String className, @Param("limit") int limit);

    @Select(ELIGIBLE + """
        select platform, count(distinct user_id) as users, max(last_success_at) as "lastSuccessAt"
        from eligible group by platform order by platform
        """)
    List<Map<String, Object>> coverage(@Param("schoolId") Long schoolId, @Param("scope") String scope, @Param("type") String type,
        @Param("metric") String metric, @Param("platform") String platform, @Param("year") Integer year,
        @Param("className") String className);

    /** 年级筛选的选项：本校参与排行的同学有哪些年级（资格同上，不看平台、指标） */
    @Select("""
        select distinct enrollment_year from school_board_account
         where school_id = #{schoolId} and enrollment_year is not null
         order by enrollment_year desc
        """)
    List<Integer> years(@Param("schoolId") Long schoolId);

    /** 班级筛选的选项：所选年级里参与排行的同学在哪些班（不选年级时是全校） */
    @Select("""
        select distinct class_name from school_board_account
         where school_id = #{schoolId} and class_name is not null and class_name <> ''
           and (#{year,jdbcType=INTEGER} is null or enrollment_year = #{year,jdbcType=INTEGER})
         order by class_name
        """)
    List<String> classes(@Param("schoolId") Long schoolId, @Param("year") Integer year);

    /** 查看者自己的成员记录与排行相关的隐私项，用来说明没上榜的具体原因 */
    @Select("""
        select coalesce(p.show_on_ranking, true) as "showOnRanking",
               coalesce(p.profile_visibility, 'SCHOOL') as "profileVisibility",
               m.enrollment_year as "enrollmentYear", m.class_name as "className"
          from school_member m
          left join user_privacy p on p.user_id = m.user_id
         where m.user_id = #{userId} and m.school_id = #{schoolId} and m.status = 'APPROVED'
        """)
    Map<String, Object> viewerMembership(@Param("userId") Long userId, @Param("schoolId") Long schoolId);

    @Select("""
        select coalesce(p.show_on_ranking, true) as "showOnRanking",
               coalesce(p.profile_visibility, 'SCHOOL') as "profileVisibility",
               coalesce((select sm.enrollment_year from school_member sm where sm.user_id=u.id and sm.status='APPROVED' order by sm.id limit 1),u.enrollment_year) as "enrollmentYear"
        from app_user u left join user_privacy p on p.user_id = u.id where u.id = #{userId}
        """)
    Map<String, Object> viewerPrivacy(@Param("userId") Long userId);

    @Select("""
        select platform, ownership_status as "ownershipStatus"
          from platform_account where user_id = #{userId} order by id
        """)
    List<Map<String, Object>> viewerAccounts(@Param("userId") Long userId);
}
