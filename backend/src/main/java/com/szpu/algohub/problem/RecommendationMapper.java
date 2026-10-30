package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 推荐题目的查询。
 *
 * 原则：只在**已同步的题库**里挑题，且只按平台原始难度筛选，不做跨平台换算，
 * 也不声称这是官方推荐或能力评估。
 */
@Mapper
public interface RecommendationMapper {

    /** 用户在某平台最近通过题目的原始难度值，用于估计当前练习区间。 */
    @Select("""
            select p.difficulty_value as "value"
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId}
               and s.platform = #{platform}
               and p.difficulty_value is not null
             order by s.earliest_known_ac_at desc nulls last
             limit #{limit}
            """)
    List<Integer> recentSolvedDifficulties(@Param("userId") Long userId, @Param("platform") String platform,
                                           @Param("limit") int limit);

    /**
     * 候选题：题库内、用户未通过、难度落在区间内的题；可按统一标签分类过滤。
     * 按平台公开的通过人数排序，热门题通常题面清晰、题解多，更适合练习。
     */
    @Select("""
            <script>
            select p.id, p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource", p.solved_count as "solvedCount"
              from problem p
             where p.platform = #{platform}
               and p.catalog_synced_at is not null
               and p.difficulty_value between #{minDifficulty} and #{maxDifficulty}
               and not exists (select 1 from user_solved_problem s
                                where s.user_id = #{userId} and s.problem_id = p.id)
            <if test="category != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            </if>
             order by p.solved_count desc nulls last, p.problem_key
             limit #{limit}
            </script>
            """)
    List<Map<String, Object>> candidates(@Param("userId") Long userId, @Param("platform") String platform,
                                         @Param("minDifficulty") int minDifficulty,
                                         @Param("maxDifficulty") int maxDifficulty,
                                         @Param("category") String category,
                                         @Param("limit") int limit);

    @Select("""
            select t.tag_raw as "tagRaw", coalesce(m.category, 'UNMAPPED') as "category"
              from problem_tag t
              left join tag_category_mapping m on m.platform = #{platform} and m.tag_raw = t.tag_raw
             where t.problem_id = #{problemId}
             order by t.tag_raw
            """)
    List<Map<String, Object>> problemTags(@Param("problemId") Long problemId,
                                          @Param("platform") String platform);

    /**
     * 某平台题库里各统一分类的题量，与用户已通过的数量对比，用于找“练得少”的方向。
     * 只统计题库已同步的题目，未同步的平台不参与。
     */
    @Select("""
            select coalesce(m.category, 'UNMAPPED') as "category",
                   count(distinct p.id) as "catalogCount",
                   count(distinct s.problem_id) as "solvedCount"
              from problem p
              join problem_tag t on t.problem_id = p.id
              left join tag_category_mapping m on m.platform = p.platform and m.tag_raw = t.tag_raw
              left join user_solved_problem s on s.problem_id = p.id and s.user_id = #{userId}
             where p.platform = #{platform}
               and p.catalog_synced_at is not null
             group by 1
             having count(distinct p.id) >= 20
             order by (count(distinct s.problem_id)::numeric / nullif(count(distinct p.id), 0)) asc
             limit #{limit}
            """)
    List<Map<String, Object>> weakCategories(@Param("userId") Long userId, @Param("platform") String platform,
                                             @Param("limit") int limit);

    @Select("""
            select count(*) from problem
             where platform = #{platform} and catalog_synced_at is not null
            """)
    int catalogSize(@Param("platform") String platform);
}
