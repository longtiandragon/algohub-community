package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StatisticsMapper {

    /** 各平台去重通过题数。未完成跨平台题目映射前，总量只能称为“各平台通过数合计”。 */
    @Select("""
            select s.platform, count(distinct s.problem_id) as solved
              from user_solved_problem s
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
             group by s.platform
             order by solved desc
            """)
    List<Map<String, Object>> platformSolved(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> platformSolved(Long userId) {
        return platformSolved(userId, false);
    }

    /** 每个已绑定账号的可确认通过总量：有逐题明细时与最新平台快照取较大值。 */
    @Select("""
            select a.platform, a.id as "accountId",
                   greatest(coalesce(d.solved, 0), coalesce(t.solved_total, 0)) as solved,
                   coalesce(d.solved, 0) as "detailSolved", t.solved_total as "snapshotSolved"
              from platform_account a
              left join lateral (
                select count(distinct s.problem_id) as solved from user_solved_problem s where s.account_id = a.id
              ) d on true
              left join lateral (
                select ts.solved_total from total_snapshot ts where ts.account_id = a.id
                 order by ts.captured_at desc limit 1
              ) t on true
             where a.user_id = #{userId} and a.platform <> 'LANQIAO' and a.ownership_status = 'VERIFIED'
             order by a.platform, a.id
            """)
    List<Map<String, Object>> accountSolvedTotals(@Param("userId") Long userId);

    /**
     * 按人工映射去重后的唯一题量。
     * 只有被明确映射到同一题目组的题目才合并计数；未映射的题目仍按平台各自计数。
     * mappedSolved 表示其中有多少条通过记录落在已建立的映射内，用于说明去重的覆盖范围。
     */
    @Select("""
            select count(distinct s.problem_id) as "totalSolved",
                   count(distinct case when m.group_id is not null
                                       then 'g:' || m.group_id
                                       else 'p:' || s.problem_id end) as "uniqueSolved",
                   count(distinct s.problem_id) filter (where m.group_id is not null) as "mappedSolved"
              from user_solved_problem s
              left join problem_group_member m on m.problem_id = s.problem_id
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
            """)
    Map<String, Object> uniqueSolvedSummary(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default Map<String, Object> uniqueSolvedSummary(Long userId) {
        return uniqueSolvedSummary(userId, false);
    }

    /** 只有总量的平台：展示最近一次快照及其时间，不与逐条记录混淆。 */
    @Select("""
            select a.platform, a.handle, a.sync_status as "syncStatus", a.last_success_at as "lastSuccessAt",
                   a.auth_mode as "authMode", a.ownership_status as "ownershipStatus",
                   t.solved_total as "solvedTotal", t.submission_total as "submissionTotal",
                   t.captured_at as "capturedAt", t.raw_extra as "rawExtra"
              from platform_account a
              left join lateral (
                    select * from total_snapshot ts
                     where ts.account_id = a.id
                     order by ts.captured_at desc limit 1
                   ) t on true
             where a.user_id = #{userId} and a.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or a.ownership_status='VERIFIED')
             order by a.platform
            """)
    List<Map<String, Object>> snapshotOverview(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> snapshotOverview(Long userId) {
        return snapshotOverview(userId, false);
    }

    /** 难度分布：保留平台原始难度，不同平台分别展示，未知难度单独成组。 */
    @Select("""
            select s.platform,
                   coalesce(p.difficulty_label, '未知') as difficulty,
                   coalesce(p.difficulty_source, 'UNKNOWN') as source,
                   count(distinct s.problem_id) as solved
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
             group by s.platform, 2, 3
             order by s.platform, solved desc
            """)
    List<Map<String, Object>> difficultyDistribution(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> difficultyDistribution(Long userId) {
        return difficultyDistribution(userId, false);
    }

    /**
     * 标签练习分布：表示练习接触情况，不等于掌握程度。
     * 未命中映射的原始标签归入 UNMAPPED，并单独统计无标签题目数量以显示覆盖范围。
     */
    @Select("""
            select coalesce(m.category, 'UNMAPPED') as category, count(distinct s.problem_id) as solved
              from user_solved_problem s
              join problem_tag t on t.problem_id = s.problem_id
              left join tag_category_mapping m on m.platform = s.platform and m.tag_raw = t.tag_raw
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
             group by 1
             order by solved desc
            """)
    List<Map<String, Object>> tagDistribution(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> tagDistribution(Long userId) {
        return tagDistribution(userId, false);
    }

    @Select("""
            select count(distinct s.problem_id) filter (where exists (select 1 from problem_tag t where t.problem_id = s.problem_id))
                       as "withTags",
                   count(distinct s.problem_id) as "total"
              from user_solved_problem s
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
            """)
    Map<String, Object> tagCoverage(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default Map<String, Object> tagCoverage(Long userId) {
        return tagCoverage(userId, false);
    }
}
