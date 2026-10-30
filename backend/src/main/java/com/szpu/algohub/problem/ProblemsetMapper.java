package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 题库浏览的查询。
 *
 * 只查**已同步进题库**的题（catalog_synced_at 不为空）：没同步过的平台在这里是空的，
 * 页面会如实说明，而不是把「我做过的那几道」当成题库。
 *
 * 「我是否通过」实时来自 user_solved_problem，不在题目行上冗余保存；
 * 难度与通过人数是平台公开值，只在同一平台内比较。
 */
@Mapper
public interface ProblemsetMapper {

    String FILTERS = """
            <if test="platform != null"> and p.platform = #{platform} </if>
            <if test="minDifficulty != null"> and p.difficulty_value &gt;= #{minDifficulty} </if>
            <if test="maxDifficulty != null"> and p.difficulty_value &lt;= #{maxDifficulty} </if>
            <if test="onlyRated"> and p.difficulty_value is not null </if>
            <if test="keyword != null">
               and (p.problem_key ilike '%' || #{keyword} || '%' escape '\\'
                    or p.title ilike '%' || #{keyword} || '%' escape '\\')
            </if>
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
            <if test="status == 'SOLVED'"> and s.problem_id is not null </if>
            <if test="status == 'UNSOLVED'"> and s.problem_id is null </if>
            """;

    @Select("""
            <script>
            select p.id, p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource", p.solved_count as "solvedCount",
                   (s.problem_id is not null) as solved,
                   (select string_agg(distinct coalesce(m.category, 'UNMAPPED'), ',')
                      from problem_tag t
                      left join tag_category_mapping m
                             on m.platform = p.platform and m.tag_raw = t.tag_raw
                     where t.problem_id = p.id) as categories
              from problem p
              left join user_solved_problem s on s.problem_id = p.id and s.user_id = #{userId}
             where p.catalog_synced_at is not null
            """ + FILTERS + """
             order by
            <choose>
              <when test="sort == 'DIFFICULTY_ASC'">p.difficulty_value asc nulls last, p.solved_count desc nulls last</when>
              <when test="sort == 'DIFFICULTY_DESC'">p.difficulty_value desc nulls last, p.solved_count desc nulls last</when>
              <when test="sort == 'KEY'">p.platform, p.problem_key</when>
              <otherwise>p.solved_count desc nulls last, p.problem_key</otherwise>
            </choose>
             , p.id
             limit #{limit} offset #{offset}
            </script>
            """)
    List<Map<String, Object>> search(@Param("userId") Long userId, @Param("platform") String platform,
                                     @Param("minDifficulty") Integer minDifficulty,
                                     @Param("maxDifficulty") Integer maxDifficulty,
                                     @Param("onlyRated") boolean onlyRated,
                                     @Param("keyword") String keyword, @Param("category") String category,
                                     @Param("status") String status, @Param("sort") String sort,
                                     @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            <script>
            select count(*)
              from problem p
              left join user_solved_problem s on s.problem_id = p.id and s.user_id = #{userId}
             where p.catalog_synced_at is not null
            """ + FILTERS + """
            </script>
            """)
    long count(@Param("userId") Long userId, @Param("platform") String platform,
               @Param("minDifficulty") Integer minDifficulty, @Param("maxDifficulty") Integer maxDifficulty,
               @Param("onlyRated") boolean onlyRated, @Param("keyword") String keyword,
               @Param("category") String category, @Param("status") String status);

    /** 各平台题库里有多少题、其中我通过了多少，用于页面顶部的范围说明。 */
    @Select("""
            select p.platform,
                   count(*) as "catalogCount",
                   count(s.problem_id) as "solvedCount",
                   min(p.difficulty_value) as "minDifficulty",
                   max(p.difficulty_value) as "maxDifficulty"
              from problem p
              left join user_solved_problem s on s.problem_id = p.id and s.user_id = #{userId}
             where p.catalog_synced_at is not null
             group by p.platform
             order by count(*) desc
            """)
    List<Map<String, Object>> catalogOverview(@Param("userId") Long userId);
}
