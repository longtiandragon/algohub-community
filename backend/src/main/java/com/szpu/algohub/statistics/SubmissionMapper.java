package com.szpu.algohub.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SubmissionMapper {

    @Select("select max(submitted_at) from submission where account_id = #{accountId} and accepted")
    Instant latestSyncedAcceptedAt(@Param("accountId") Long accountId);

    /** 以平台与提交 ID 幂等入库，重复抓取不重复计数。 */
    @Insert("""
            insert into submission (account_id, problem_id, platform, platform_sub_id, verdict, accepted,
                                    submitted_at, time_precision, language, contest_ref, collected_at)
            values (#{accountId}, #{problemId,jdbcType=BIGINT}, #{platform}, #{platformSubId}, #{verdict},
                    #{accepted}, #{submittedAt}, #{timePrecision}, #{language,jdbcType=VARCHAR},
                    #{contestRef,jdbcType=VARCHAR}, now())
            on conflict (account_id, platform, platform_sub_id) do update
               set verdict = excluded.verdict,
                   accepted = excluded.accepted,
                   problem_id = case when submission.time_precision = 'USER_REPORTED'
                                     then excluded.problem_id else submission.problem_id end,
                   submitted_at = case when submission.time_precision = 'USER_REPORTED'
                                       then excluded.submitted_at else submission.submitted_at end,
                   time_precision = case when submission.time_precision = 'USER_REPORTED'
                                         then excluded.time_precision else submission.time_precision end,
                   language = case when submission.time_precision = 'USER_REPORTED'
                                   then excluded.language else submission.language end,
                   contest_ref = case when submission.time_precision = 'USER_REPORTED'
                                      then excluded.contest_ref else submission.contest_ref end,
                   collected_at = now()
            """)
    int upsert(@Param("accountId") Long accountId, @Param("problemId") Long problemId,
               @Param("platform") String platform, @Param("platformSubId") String platformSubId,
               @Param("verdict") String verdict, @Param("accepted") boolean accepted,
               @Param("submittedAt") Instant submittedAt, @Param("timePrecision") String timePrecision,
               @Param("language") String language, @Param("contestRef") String contestRef);

    /** 客户端记录保留自报标签，不能覆盖同 ID 的源站提交。 */
    @Insert("""
            insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,
                                   submitted_at,time_precision,language)
            values (#{accountId},#{problemId},'LUOGU',#{recordId},'AC',true,
                    #{at},'USER_REPORTED',#{language,jdbcType=VARCHAR})
            on conflict (account_id,platform,platform_sub_id) do nothing
            """)
    int insertBrowserRecord(@Param("accountId") Long accountId, @Param("problemId") Long problemId,
                            @Param("recordId") String recordId, @Param("at") Instant at,
                            @Param("language") String language);

    @Insert("""
            insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,
                                   submitted_at,time_precision,language)
            values (#{accountId},#{problemId},'QOJ',#{recordId},'AC',true,
                    #{at},'USER_REPORTED',#{language,jdbcType=VARCHAR})
            on conflict (account_id,platform,platform_sub_id) do nothing
            """)
    int insertQojBrowserRecord(@Param("accountId") Long accountId, @Param("problemId") Long problemId,
                              @Param("recordId") String recordId, @Param("at") Instant at,
                              @Param("language") String language);

    @Insert("""
            insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,
                                   submitted_at,time_precision,language)
            values (#{accountId},#{problemId},'LEETCODE_CN',#{recordId},'AC',true,
                    #{at},'USER_REPORTED',#{language,jdbcType=VARCHAR})
            on conflict (account_id,platform,platform_sub_id) do nothing
            """)
    int insertLeetCodeBrowserRecord(@Param("accountId") Long accountId, @Param("problemId") Long problemId,
                              @Param("recordId") String recordId, @Param("at") Instant at,
                              @Param("language") String language);

    @Select("select count(*) from submission where account_id = #{accountId}")
    int countByAccount(@Param("accountId") Long accountId);

    @Select("""
            select count(*) from submission
             where account_id = #{accountId} and submitted_at >= #{since}
            """)
    int countRecent(@Param("accountId") Long accountId, @Param("since") Instant since);

    @Select("""
            select s.platform, s.verdict, s.submitted_at as "submittedAt", s.accepted,
                   s.time_precision as "timePrecision", p.title, p.url, p.problem_key as "problemKey",
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue"
              from (
                  select account_id,problem_id,platform,verdict,submitted_at,accepted,time_precision from submission
                  union all
                  select u.account_id,u.problem_id,u.platform,'AC',u.earliest_known_ac_at,true,u.first_ac_confidence
                  from user_solved_problem u
                  where not exists(select 1 from submission actual where actual.account_id=u.account_id
                                   and actual.problem_id=u.problem_id and actual.accepted)
              ) s
              join platform_account a on a.id = s.account_id
              left join problem p on p.id = s.problem_id
             where a.user_id = #{userId} and a.platform <> 'LANQIAO' and s.accepted = true
               and (#{verifiedOnly}=false or a.ownership_status='VERIFIED')
             order by s.submitted_at desc nulls last
             limit #{limit}
            """)
    List<Map<String, Object>> recentAccepted(@Param("userId") Long userId, @Param("limit") int limit,
                                            @Param("verifiedOnly") boolean verifiedOnly);

    default List<Map<String, Object>> recentAccepted(Long userId, int limit) {
        return recentAccepted(userId, limit, false);
    }
}
