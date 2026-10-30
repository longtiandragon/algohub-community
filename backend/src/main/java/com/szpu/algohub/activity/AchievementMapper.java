package com.szpu.algohub.activity;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 成就与每日一题的查询。
 *
 * 成就全部由现有数据推导，不另存一张“已解锁”表：
 * 这样重算统计、修正记录或解绑账号后，成就会跟着回退，不会留下对不上的历史标记。
 * 所有口径都只覆盖“已同步入库”的记录。
 */
@Mapper
public interface AchievementMapper {

    /** 去重通过题数（各平台分别去重后的合计）。 */
    @Select("""
            select count(distinct s.problem_id) from user_solved_problem s
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
            """)
    int totalSolved(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default int totalSolved(Long userId) {
        return totalSolved(userId, false);
    }

    /** 有难度记录的通过题中的最高原始难度，只在同平台内有意义。 */
    @Select("""
            select s.platform, max(p.difficulty_value) as "maxDifficulty",
                   count(distinct s.problem_id) filter (where p.difficulty_value is not null) as "ratedSolved"
              from user_solved_problem s
              join problem p on p.id = s.problem_id
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
             group by s.platform
             order by s.platform
            """)
    List<Map<String, Object>> difficultyPeaks(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> difficultyPeaks(Long userId) {
        return difficultyPeaks(userId, false);
    }

    /** 已覆盖的统一标签分类数量，以及各分类通过题数。 */
    @Select("""
            select coalesce(m.category, 'UNMAPPED') as "category",
                   count(distinct s.problem_id) as "solved"
              from user_solved_problem s
              join problem_tag t on t.problem_id = s.problem_id
              left join tag_category_mapping m on m.platform = s.platform and m.tag_raw = t.tag_raw
             where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
             group by 1
             order by solved desc
            """)
    List<Map<String, Object>> categoryCoverage(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> categoryCoverage(Long userId) {
        return categoryCoverage(userId, false);
    }

    /** 有通过记录的平台数量。 */
    @Select("""
            select count(distinct s.platform) from user_solved_problem s where s.user_id = #{userId} and s.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or exists (select 1 from platform_account a
                    where a.id=s.account_id and a.ownership_status='VERIFIED'))
            """)
    int platformsWithSolves(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default int platformsWithSolves(Long userId) {
        return platformsWithSolves(userId, false);
    }

    /**
     * 涉及比赛的提交覆盖到的场次数量。
     * contest_ref 只表示该提交属于某场比赛的题目，不等于“参加了该场比赛的正式计分”。
     */
    @Select("""
            select count(distinct s.contest_ref)
              from submission s
              join platform_account a on a.id = s.account_id
             where a.user_id = #{userId} and a.platform <> 'LANQIAO'
               and (#{verifiedOnly}=false or a.ownership_status='VERIFIED')
               and s.contest_ref is not null
               and s.contest_ref <> ''
            """)
    int contestsTouched(@Param("userId") Long userId, @Param("verifiedOnly") boolean verifiedOnly);

    default int contestsTouched(Long userId) {
        return contestsTouched(userId, false);
    }

    /**
     * 每日一题的候选池：题库内、指定难度区间的题。
     * 通过人数只用于排序（热门题题解多、更适合练习），平台不提供时排在后面，
     * 不能因为缺这个字段就把整个平台排除掉。
     */
    @Select("""
            select p.id, p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource", p.solved_count as "solvedCount"
              from problem p
             where p.platform = #{platform}
               and p.catalog_synced_at is not null
               and p.difficulty_value between #{minDifficulty} and #{maxDifficulty}
             order by p.solved_count desc nulls last, p.problem_key
             limit #{poolSize}
            """)
    List<Map<String, Object>> dailyCandidates(@Param("platform") String platform,
                                              @Param("minDifficulty") int minDifficulty,
                                              @Param("maxDifficulty") int maxDifficulty,
                                              @Param("poolSize") int poolSize);

    @Select("""
            select count(*) from user_solved_problem
             where user_id = #{userId} and problem_id = #{problemId}
            """)
    int solvedByUser(@Param("userId") Long userId, @Param("problemId") Long problemId);

    /** 本校有多少同学通过了这道题：参与资格统一取自 school_board_account。 */
    @Select("""
            select count(distinct s.user_id)
              from user_solved_problem s
              join school_board_account e on e.account_id = s.account_id and e.school_id = #{schoolId}
             where s.problem_id = #{problemId}
            """)
    int campusSolvedCount(@Param("schoolId") Long schoolId, @Param("problemId") Long problemId);

    @Select("""
            select t.tag_raw as "tagRaw", coalesce(m.category, 'UNMAPPED') as "category"
              from problem_tag t
              left join tag_category_mapping m on m.platform = #{platform} and m.tag_raw = t.tag_raw
             where t.problem_id = #{problemId}
             order by t.tag_raw
            """)
    List<Map<String, Object>> problemTags(@Param("problemId") Long problemId,
                                          @Param("platform") String platform);
}
