package com.szpu.algohub.activity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 刷题动态的查询。
 *
 * 动态不是另存一份数据，而是 user_solved_problem 的时间线视角：
 * 同步、重算或纠错后动态会跟着变，不会出现两份互相矛盾的记录。
 *
 * 可见性规则（与排行榜一致，统一取自视图 school_board_account）：
 * - 只包含查看者本校已通过成员的、已验证归属的账号；
 * - 只包含有已知通过时间的记录，没有时间的不猜测；
 * - 排除"不参与排行榜"或"档案仅自己可见"的用户。
 * 学校由服务端按查看者的成员记录传入；year 不为空时只看那一届（学校成员登记的入学年级，「同届」筛选）。
 *
 * 个人的逐条提交与导出在「我的档案 → 刷题历史与导出」（/api/history），这里不重复提供。
 */
@Mapper
public interface ActivityMapper {
    @Select("""
        select a.id as "accountId",a.platform,a.last_success_at as "lastSuccessAt",
               count(s.id) as "solvedCount",
               count(s.id) filter(where s.earliest_known_ac_at is null) as "untimedCount"
        from platform_account a left join user_solved_problem s on s.account_id=a.id
        where a.user_id=#{userId} and a.platform <> 'LANQIAO'
        group by a.id order by a.platform
        """)
    List<Map<String,Object>> ownCoverage(@Param("userId") Long userId);

    // 先在本校可见账号里取每人每题最早的一条，再做游标与日期筛选；传了入学年级就只取那一届的同学。
    String VISIBLE_SOLVES = """
            (select distinct on (s.user_id, s.problem_id) s.*
               from (select s.*,
                            bool_and(s.first_ac_confidence='EXACT' and s.earliest_known_ac_at is not null)
                              over (partition by s.user_id,s.problem_id) as exact_time
                       from user_solved_problem s
                       join activity_board_account e on e.account_id = s.account_id
                            and e.school_id is not distinct from #{schoolId,jdbcType=BIGINT}
                            and (cast(#{year,jdbcType=VARCHAR} as text) is null
                                 or e.enrollment_year = any(string_to_array(#{year,jdbcType=VARCHAR}, ',')::integer[]))) s
              where s.earliest_known_ac_at is not null
              order by s.user_id,s.problem_id,s.earliest_known_ac_at,s.id)
            """;

    String FEED_SELECT = """
            select s.id,
                   s.user_id as "userId",
                   s.problem_id as "problemId",
                   u.display_name as "displayName",
                   case when coalesce(privacy.show_accounts,true) then u.avatar_url else null end as "avatarUrl",
                   s.platform,
                   p.problem_key as "problemKey",
                   p.title,
                   p.url,
                   p.difficulty_label as "difficultyLabel",
                   p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource",
                   s.earliest_known_ac_at as "acAt",
                   case when s.first_ac_confidence='USER_REPORTED' then 'USER_REPORTED'
                        when s.exact_time then 'EXACT' else 'EARLIEST_KNOWN' end as "firstAcConfidence",
                   s.source
              from """ + VISIBLE_SOLVES + """
               s
              join app_user u on u.id = s.user_id
              left join user_privacy privacy on privacy.user_id=u.id
              join problem p on p.id = s.problem_id
             where s.earliest_known_ac_at is not null
            """;

    /**
     * 校内动态。cursorAt / cursorId 为上一页最后一条的位置，用行值比较翻页，
     * 避免 offset 在新数据插入时出现重复或漏项。
     */
    @Select("<script>" + FEED_SELECT + """
            <if test="platform != null"> and s.platform = #{platform} </if>
            <if test="userId != null"> and s.user_id = #{userId} </if>
            <if test="followingViewerId != null">
                and exists(select 1 from user_follow f where f.follower_id=#{followingViewerId} and f.following_id=s.user_id)
            </if>
            <if test="cursorAt != null">
               and (s.earliest_known_ac_at, s.id) &lt; (#{cursorAt}, #{cursorId})
            </if>
             order by s.earliest_known_ac_at desc, s.id desc
             limit #{limit}
            </script>""")
    List<Map<String, Object>> filteredFeed(@Param("schoolId") Long schoolId,
                                         @Param("year") String year,
                                         @Param("platform") String platform,
                                         @Param("userId") Long userId,
                                         @Param("cursorAt") Instant cursorAt,
                                         @Param("cursorId") Long cursorId,
                                         @Param("limit") int limit,
                                         @Param("followingViewerId") Long followingViewerId);

    default List<Map<String,Object>> schoolFeed(Long schoolId,String year,String platform,Long userId,Instant cursorAt,Long cursorId,int limit) {
        return filteredFeed(schoolId,year,platform,userId,cursorAt,cursorId,limit,null);
    }

    /** 校内动态的参与人数与最近一条时间，用于页面说明覆盖范围。 */
    @Select("""
            select count(distinct s.user_id) as "activeUsers",
                   count(*) as "totalEvents",
                   max(s.earliest_known_ac_at) as "latestAt"
              from """ + VISIBLE_SOLVES + """
               s
             where s.earliest_known_ac_at is not null
               and s.earliest_known_ac_at >= #{since}
            """)
    Map<String, Object> feedSummary(@Param("schoolId") Long schoolId, @Param("year") String year,
                                    @Param("since") Instant since);

    /**
     * 动态里各平台各有多少条、最近一条什么时候（不限时间，和动态列表同一范围）。
     * 动态按时间倒序、不分平台，时间较早的平台要往下翻才看得到，页面用这份数据说清哪些平台会出现。
     */
    @Select("""
            select s.platform, count(*) as "events", max(s.earliest_known_ac_at) as "latestAt"
              from """ + VISIBLE_SOLVES + """
               s
             group by s.platform
             order by max(s.earliest_known_ac_at) desc
            """)
    List<Map<String, Object>> feedPlatforms(@Param("schoolId") Long schoolId, @Param("year") String year);

    /**
     * 本校（这一届）参与展示、已经有通过数据的账号在哪些平台：有逐题通过，或者平台统计题数大于 0（码蹄集只有总数）。
     * 减去动态里有的，就是拿不到通过时间的平台；一道题都还没做的平台不算。
     */
    @Select("""
            select distinct a.platform
              from activity_board_account e
              join platform_account a on a.id = e.account_id
             where e.school_id is not distinct from #{schoolId,jdbcType=BIGINT}
               and (cast(#{year,jdbcType=VARCHAR} as text) is null or e.enrollment_year = any(string_to_array(#{year,jdbcType=VARCHAR}, ',')::integer[]))
               and (exists (select 1 from user_solved_problem s where s.account_id = a.id)
                    or exists (select 1 from total_snapshot t where t.account_id = a.id and t.solved_total > 0))
            """)
    List<String> boardPlatforms(@Param("schoolId") Long schoolId, @Param("year") String year);

    /** 连续刷题天数等激励用统计，按 Asia/Shanghai 自然日计算。 */
    @Select("""
            select (s.earliest_known_ac_at at time zone 'Asia/Shanghai')::date as "day",
                   count(*) as "solved"
              from (select user_id,problem_id,min(earliest_known_ac_at) as earliest_known_ac_at
                      from user_solved_problem records where records.user_id=#{userId} and records.platform <> 'LANQIAO'
                        and (records.first_ac_confidence <> 'USER_REPORTED' or records.platform in ('LUOGU','QOJ','LEETCODE_CN'))
                        and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                             where a.id=records.account_id and a.ownership_status='VERIFIED'))
                     group by user_id,problem_id) s
             where s.user_id = #{userId}
               and s.earliest_known_ac_at is not null
             group by 1
             order by 1 desc
            """)
    List<Map<String, Object>> dailySolveDays(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> dailySolveDays(Long userId) {
        return dailySolveDays(userId, false);
    }
}
