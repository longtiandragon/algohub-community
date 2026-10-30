package com.szpu.algohub.goal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 周目标的存取与进度计算。
 *
 * 进度不存表，每次都按「通过时间落在本周窗口内」实时统计；
 * 没有已知通过时间的记录（例如洛谷题单、Tracker 缓存）**不计入**，
 * 但会单独数出来告诉用户，免得以为平台漏算了。
 */
@Mapper
public interface WeeklyGoalMapper {

    @Update("""
            insert into weekly_goal (user_id, week_start, target, category, platform)
            values (#{userId}, #{weekStart}, #{target}, #{category}, #{platform})
            on conflict (user_id, week_start) do update
               set target = excluded.target,
                   category = excluded.category,
                   platform = excluded.platform,
                   updated_at = now()
            """)
    int upsert(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart,
               @Param("target") int target, @Param("category") String category,
               @Param("platform") String platform);

    @Delete("delete from weekly_goal where user_id = #{userId} and week_start = #{weekStart}")
    int remove(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart);

    @Select("""
            select id, week_start as "weekStart", target, category, platform, updated_at as "updatedAt"
              from weekly_goal
             where user_id = #{userId} and week_start = #{weekStart}
            """)
    Map<String, Object> find(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart);

    @Select("""
            select id, week_start as "weekStart", target, category, platform
              from weekly_goal
             where user_id = #{userId} and week_start < #{weekStart}
             order by week_start desc
             limit #{limit}
            """)
    List<Map<String, Object>> recent(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart,
                                     @Param("limit") int limit);

    /**
     * 某个时间窗口内新通过的题数。可按平台与统一算法分类限定范围。
     * 只数有已知通过时间的记录，时间未知的不猜测。
     */
    @Select("""
            <script>
            select count(distinct s.problem_id)
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId}
               and s.earliest_known_ac_at &gt;= #{from}
               and s.earliest_known_ac_at &lt; #{until}
            <if test="platform != null">
               and s.platform = #{platform}
            </if>
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
            </script>
            """)
    int solvedInWindow(@Param("userId") Long userId, @Param("from") Instant from,
                       @Param("until") Instant until, @Param("platform") String platform,
                       @Param("category") String category);

    /** 本周窗口内新通过的题，按时间倒序，让进度可以逐题核对。 */
    @Select("""
            <script>
            select p.id, s.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel",
                   p.difficulty_value as "difficultyValue",
                   s.earliest_known_ac_at as "acAt", s.first_ac_confidence as "firstAcConfidence"
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId}
               and s.earliest_known_ac_at &gt;= #{from}
               and s.earliest_known_ac_at &lt; #{until}
            <if test="platform != null">
               and s.platform = #{platform}
            </if>
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
             order by s.earliest_known_ac_at desc, p.id desc
             limit #{limit}
            </script>
            """)
    List<Map<String, Object>> solvedInWindowList(@Param("userId") Long userId, @Param("from") Instant from,
                                                 @Param("until") Instant until,
                                                 @Param("platform") String platform,
                                                 @Param("category") String category,
                                                 @Param("limit") int limit);

    /**
     * 本周新入库、但**没有已知通过时间**的记录数。
     * 这些题确实做了，只是源站不提供时间，不能算进「本周新通过」，页面上单独说明。
     */
    @Select("""
            <script>
            select count(distinct s.problem_id)
              from user_solved_problem s
             where s.user_id = #{userId}
               and s.earliest_known_ac_at is null
               and s.created_at &gt;= #{from}
               and s.created_at &lt; #{until}
            <if test="platform != null">
               and s.platform = #{platform}
            </if>
            </script>
            """)
    int addedWithoutTime(@Param("userId") Long userId, @Param("from") Instant from,
                         @Param("until") Instant until, @Param("platform") String platform);
}
