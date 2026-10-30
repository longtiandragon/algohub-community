package com.szpu.algohub.bookmark;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 收藏与复习记录的查询。
 *
 * 所有查询都以 user_id 为条件，不提供按他人 ID 查询的入口：笔记只有本人能读。
 * 「是否已通过」实时来自 user_solved_problem，不在收藏表里冗余保存，
 * 因此重算统计或解绑账号后，收藏卡片上的通过状态会跟着变，不会留下对不上的标记。
 */
@Mapper
public interface BookmarkMapper {

    @Update("""
            insert into problem_bookmark (user_id, problem_id, status, note, remind_on)
            values (#{userId}, #{problemId}, #{status}, #{note}, #{remindOn})
            on conflict (user_id, problem_id) do update
               set status = excluded.status,
                   note = excluded.note,
                   remind_on = excluded.remind_on,
                   updated_at = now()
            """)
    int upsert(@Param("userId") Long userId, @Param("problemId") Long problemId,
               @Param("status") String status, @Param("note") String note,
               @Param("remindOn") LocalDate remindOn);

    @Delete("delete from problem_bookmark where user_id = #{userId} and problem_id = #{problemId}")
    int remove(@Param("userId") Long userId, @Param("problemId") Long problemId);

    @Select("""
            select count(*) from problem where id = #{problemId}
            """)
    int problemExists(@Param("problemId") Long problemId);

    /**
     * 收藏列表。可按状态、平台、统一标签分类筛选；
     * accepted 来自 user_solved_problem，是平台数据，与自报的 status 分开展示。
     */
    @Select("""
            <script>
            select b.id, b.problem_id as "problemId", b.status, b.note, b.remind_on as "remindOn",
                   b.created_at as "createdAt", b.updated_at as "updatedAt",
                   p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource",
                   (s.problem_id is not null) as accepted,
                   s.earliest_known_ac_at as "acAt",
                   (select string_agg(distinct coalesce(m.category, 'UNMAPPED'), ',')
                      from problem_tag t
                      left join tag_category_mapping m
                             on m.platform = p.platform and m.tag_raw = t.tag_raw
                     where t.problem_id = p.id) as "categories"
              from problem_bookmark b
              join problem p on p.id = b.problem_id
              left join user_solved_problem s on s.problem_id = b.problem_id and s.user_id = b.user_id
             where b.user_id = #{userId}
            <if test="status != null">
               and b.status = #{status}
            </if>
            <if test="platform != null">
               and p.platform = #{platform}
            </if>
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
            <if test="dueOnly">
               and b.remind_on is not null and b.remind_on &lt;= #{today}
            </if>
             order by b.updated_at desc, b.id desc
             limit #{limit} offset #{offset}
            </script>
            """)
    List<Map<String, Object>> list(@Param("userId") Long userId, @Param("status") String status,
                                   @Param("platform") String platform, @Param("category") String category,
                                   @Param("dueOnly") boolean dueOnly, @Param("today") LocalDate today,
                                   @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            <script>
            select count(*)
              from problem_bookmark b
              join problem p on p.id = b.problem_id
             where b.user_id = #{userId}
            <if test="status != null">
               and b.status = #{status}
            </if>
            <if test="platform != null">
               and p.platform = #{platform}
            </if>
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
            <if test="dueOnly">
               and b.remind_on is not null and b.remind_on &lt;= #{today}
            </if>
            </script>
            """)
    long count(@Param("userId") Long userId, @Param("status") String status,
               @Param("platform") String platform, @Param("category") String category,
               @Param("dueOnly") boolean dueOnly, @Param("today") LocalDate today);

    /** 各状态的条数与「到期待复习」条数，用于页面上的筛选计数。 */
    @Select("""
            select b.status, count(*) as "count",
                   count(*) filter (where b.remind_on is not null and b.remind_on <= #{today}) as "due"
              from problem_bookmark b
             where b.user_id = #{userId}
             group by b.status
            """)
    List<Map<String, Object>> statusCounts(@Param("userId") Long userId, @Param("today") LocalDate today);

    /** 单条收藏，用于「这题我收藏过吗」以及收藏后回显。 */
    @Select("""
            select b.problem_id as "problemId", b.status, b.note, b.remind_on as "remindOn",
                   b.updated_at as "updatedAt"
              from problem_bookmark b
             where b.user_id = #{userId} and b.problem_id = #{problemId}
            """)
    Map<String, Object> findOne(@Param("userId") Long userId, @Param("problemId") Long problemId);

    /** 一批题目里哪些已经收藏过，供列表页一次性标出收藏状态，避免每题一次请求。 */
    @Select("""
            <script>
            select b.problem_id as "problemId", b.status
              from problem_bookmark b
             where b.user_id = #{userId}
               and b.problem_id in
            <foreach item="id" collection="problemIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> findMany(@Param("userId") Long userId,
                                       @Param("problemIds") List<Long> problemIds);
}
