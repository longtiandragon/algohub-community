package com.szpu.algohub.activity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 校内训练活动的查询。
 *
 * 进度不存表，全部实时算：题单里的题，通过时间落在活动窗口内的算「活动期间完成」，
 * 早于开始时间的算「活动前已通过」，两者分开数。
 * 通过时间未知的记录两边都不算，单独报数——源站没给时间就是没给，不猜。
 */
@Mapper
public interface TrainingActivityMapper {

    @Update("""
            insert into training_activity (title, description, list_id, start_at, end_at, published,
                                           created_by, scope, school_id)
            values (#{title}, #{description}, #{listId}, #{startAt}, #{endAt}, #{published}, #{createdBy},
                    #{scope}, #{schoolId,jdbcType=BIGINT})
            """)
    int insert(@Param("title") String title, @Param("description") String description,
               @Param("listId") Long listId, @Param("startAt") Instant startAt,
               @Param("endAt") Instant endAt, @Param("published") boolean published,
               @Param("createdBy") Long createdBy, @Param("scope") String scope,
               @Param("schoolId") Long schoolId);

    @Update("""
            update training_activity
               set title = #{title}, description = #{description}, list_id = #{listId},
                   start_at = #{startAt}, end_at = #{endAt}, published = #{published},
                   scope = #{scope}, school_id = #{schoolId,jdbcType=BIGINT},
                   updated_at = now()
             where id = #{id}
            """)
    int update(@Param("id") Long id, @Param("title") String title,
               @Param("description") String description, @Param("listId") Long listId,
               @Param("startAt") Instant startAt, @Param("endAt") Instant endAt,
               @Param("published") boolean published, @Param("scope") String scope,
               @Param("schoolId") Long schoolId);

    @Delete("delete from training_activity where id = #{id}")
    int delete(@Param("id") Long id);

    @Select("""
            select a.id from training_activity a where a.id = #{id}
            """)
    Long exists(@Param("id") Long id);

    String SELECT = """
            select a.id, a.title, a.description, a.list_id as "listId",
                   a.start_at as "startAt", a.end_at as "endAt", a.published,
                   a.scope, a.school_id as "schoolId", sc.name as "schoolName",
                   a.created_at as "createdAt",
                   l.title as "listTitle", l.code as "listCode",
                   (select count(*) from problem_list_item i where i.list_id = a.list_id) as "problemCount"
              from training_activity a
              left join problem_list l on l.id = a.list_id
              left join school sc on sc.id = a.school_id
            """;

    /**
     * 活动列表。学生视角（adminView = false）只看已发布、且面向全站或面向本校的活动；
     * 本校由服务端按成员记录传入，没有学校时只能看到全站活动。管理员视角看全部。
     */
    @Select("<script>" + SELECT + """
            <if test="!adminView">
             where a.published = true
               and (a.scope = 'PUBLIC' or a.school_id = #{schoolId,jdbcType=BIGINT})
            </if>
             order by a.start_at desc, a.id desc
             limit #{limit}
            </script>""")
    List<Map<String, Object>> list(@Param("adminView") boolean adminView, @Param("schoolId") Long schoolId,
                                   @Param("limit") int limit);

    @Select(SELECT + " where a.id = #{id}")
    Map<String, Object> find(@Param("id") Long id);

    /**
     * 我在这个活动里的进度：题单里的题，分成「活动期间通过」「活动前已通过」
     * 与「有通过记录但时间未知」三类。三类互不重叠。
     */
    @Select("""
            select count(distinct i.problem_id) filter (
                       where s.earliest_known_ac_at >= #{startAt}
                         and s.earliest_known_ac_at < #{endAt}) as "duringCount",
                   count(distinct i.problem_id) filter (
                       where s.earliest_known_ac_at < #{startAt}) as "beforeCount",
                   count(distinct i.problem_id) filter (
                       where s.problem_id is not null
                         and s.earliest_known_ac_at is null) as "unknownTimeCount",
                   count(distinct i.problem_id) as "problemCount"
              from problem_list_item i
              left join user_solved_problem s
                     on s.problem_id = i.problem_id and s.user_id = #{userId}
             where i.list_id = #{listId}
            """)
    Map<String, Object> progress(@Param("userId") Long userId, @Param("listId") Long listId,
                                 @Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

    /** 活动里的题目逐条状态，让进度可以核对。 */
    @Select("""
            select p.id, p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   i.position,
                   s.earliest_known_ac_at as "acAt",
                   case
                     when s.problem_id is null then 'TODO'
                     when s.earliest_known_ac_at is null then 'SOLVED_TIME_UNKNOWN'
                     when s.earliest_known_ac_at >= #{startAt} and s.earliest_known_ac_at < #{endAt}
                          then 'SOLVED_DURING'
                     when s.earliest_known_ac_at < #{startAt} then 'SOLVED_BEFORE'
                     else 'SOLVED_AFTER'
                   end as status
              from problem_list_item i
              join problem p on p.id = i.problem_id
              left join user_solved_problem s
                     on s.problem_id = i.problem_id and s.user_id = #{userId}
             where i.list_id = #{listId}
             order by i.position, i.id
            """)
    List<Map<String, Object>> problems(@Param("userId") Long userId, @Param("listId") Long listId,
                                       @Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

    /**
     * 活动榜：只数「活动期间通过」的题，按题数排，同数时先完成的在前。
     * 只列查看者本校的同学（全站活动也一样），参与资格统一取自 school_board_account。
     */
    @Select("""
            select e.user_id as "userId", e.display_name as "displayName", e.avatar_url as "avatarUrl",
                   count(distinct i.problem_id) as "solvedCount",
                   max(s.earliest_known_ac_at) as "lastSolvedAt"
              from problem_list_item i
              join user_solved_problem s on s.problem_id = i.problem_id
              join school_board_account e on e.account_id = s.account_id and e.school_id = #{schoolId}
             where i.list_id = #{listId}
               and s.earliest_known_ac_at >= #{startAt}
               and s.earliest_known_ac_at < #{endAt}
             group by e.user_id, e.display_name, e.avatar_url
             order by count(distinct i.problem_id) desc, max(s.earliest_known_ac_at)
             limit #{limit}
            """)
    List<Map<String, Object>> leaderboard(@Param("schoolId") Long schoolId, @Param("listId") Long listId,
                                          @Param("startAt") Instant startAt, @Param("endAt") Instant endAt,
                                          @Param("limit") int limit);
}
