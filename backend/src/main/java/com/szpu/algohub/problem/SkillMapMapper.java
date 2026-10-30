package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 算法地图的查询：按统一标签分类，对比「我通过的题」与「题库里的题」。
 *
 * 口径必须记牢，页面上也照样写：
 * - 一道题可能带多个标签，会在多个分类里各计一次，所以各分类之和大于题目总数；
 * - 只统计该平台题库里**带标签**的题；没有公开标签的平台（例如 AtCoder）整张地图都是空的；
 * - 难度是平台原始值，只在同一平台内比较，不做跨平台换算；
 * - 覆盖率低不等于是弱项，可能只是这个方向的题本来就多、或标签本身不完整。
 */
@Mapper
public interface SkillMapMapper {

    /**
     * 各统一分类下：题库题量、我通过的题数、通过题里的最高难度、最近一次通过时间、近 30 天新增。
     *
     * catalogCount 只数题库同步过来的题；solvedCount 不受此限制（Gym、近期比赛的题目不在题库里，
     * 但确实通过了）。因此覆盖率的分子必须另取 solvedInCatalog，否则分子里会混进分母根本
     * 没统计的题，算出来的比例不成立，极端情况下还会超过 100%。
     */
    @Select("""
            select coalesce(m.category, 'UNMAPPED') as "category",
                   count(distinct p.id) filter (where p.catalog_synced_at is not null) as "catalogCount",
                   count(distinct s.problem_id) as "solvedCount",
                   count(distinct s.problem_id) filter (
                       where p.catalog_synced_at is not null) as "solvedInCatalog",
                   max(p.difficulty_value) filter (where s.problem_id is not null) as "maxSolvedDifficulty",
                   max(s.earliest_known_ac_at) as "lastSolvedAt",
                   count(distinct s.problem_id) filter (
                       where s.earliest_known_ac_at >= now() - interval '30 days') as "recentSolved"
              from problem p
              join problem_tag t on t.problem_id = p.id
              left join tag_category_mapping m on m.platform = p.platform and m.tag_raw = t.tag_raw
              left join user_solved_problem s on s.problem_id = p.id and s.user_id = #{userId}
             where p.platform = #{platform}
             group by 1
             order by count(distinct s.problem_id) desc, count(distinct p.id) desc
            """)
    List<Map<String, Object>> categories(@Param("userId") Long userId, @Param("platform") String platform);

    /**
     * 没指定平台时的默认选择：我通过的题里带标签最多的那个平台。
     * 只绑定了洛谷的同学不该一进来就看到一张全是 0 的 Codeforces 地图。
     * 一道带标签的题都没有时返回 null，由上层回退到默认平台。
     */
    @Select("""
            select s.platform
              from user_solved_problem s
             where s.user_id = #{userId}
               and exists (select 1 from problem_tag t where t.problem_id = s.problem_id)
             group by s.platform
             order by count(distinct s.problem_id) desc, s.platform
             limit 1
            """)
    String platformWithMostTaggedSolves(@Param("userId") Long userId);

    /** 该平台我通过了多少题、其中多少题在题库里带标签——用来如实说明这张地图覆盖了多少。 */
    @Select("""
            select count(distinct s.problem_id) as "solvedTotal",
                   count(distinct s.problem_id) filter (
                       where exists (select 1 from problem_tag t where t.problem_id = s.problem_id)) as "taggedSolved"
              from user_solved_problem s
             where s.user_id = #{userId} and s.platform = #{platform}
            """)
    Map<String, Object> solvedCoverage(@Param("userId") Long userId, @Param("platform") String platform);

    /** 某分类下我已经通过的题，最近通过的排在前面。 */
    @Select("""
            select p.id, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource",
                   s.earliest_known_ac_at as "acAt", s.first_ac_confidence as "firstAcConfidence",
                   s.source
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId}
               and p.platform = #{platform}
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
             order by s.earliest_known_ac_at desc nulls last, p.problem_key
             limit #{limit}
            """)
    List<Map<String, Object>> solvedInCategory(@Param("userId") Long userId, @Param("platform") String platform,
                                               @Param("category") String category, @Param("limit") int limit);

    /**
     * 某分类下还没通过的题：优先挑难度贴近自己水平的，再按平台公开的通过人数排序。
     * 没给难度区间时（level 为 null）就只按热度排，并在返回里说明。
     */
    @Select("""
            <script>
            select p.id, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource", p.solved_count as "solvedCount"
              from problem p
             where p.platform = #{platform}
               and p.catalog_synced_at is not null
               and not exists (select 1 from user_solved_problem s
                                where s.user_id = #{userId} and s.problem_id = p.id)
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
            <if test="minDifficulty != null">
               and p.difficulty_value between #{minDifficulty} and #{maxDifficulty}
            </if>
             order by p.solved_count desc nulls last, p.problem_key
             limit #{limit}
            </script>
            """)
    List<Map<String, Object>> todoInCategory(@Param("userId") Long userId, @Param("platform") String platform,
                                             @Param("category") String category,
                                             @Param("minDifficulty") Integer minDifficulty,
                                             @Param("maxDifficulty") Integer maxDifficulty,
                                             @Param("limit") int limit);

    /** 某分类下我通过的题的难度分布，按平台原始难度分桶，用来看“练的是哪一档”。 */
    @Select("""
            select p.difficulty_value as "difficultyValue", count(distinct p.id) as "count"
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId}
               and p.platform = #{platform}
               and p.difficulty_value is not null
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{category})
             group by 1
             order by 1
            """)
    List<Map<String, Object>> difficultyHistogram(@Param("userId") Long userId,
                                                  @Param("platform") String platform,
                                                  @Param("category") String category);
}
