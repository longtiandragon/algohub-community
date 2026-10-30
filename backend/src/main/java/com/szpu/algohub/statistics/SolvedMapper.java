package com.szpu.algohub.statistics;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SolvedMapper {

    /**
     * 去重后的通过关系。earliest_known_ac_at 只会向更早的时间修正，
     * 表示“当前已知的最早通过时间”，与真正的首次 AC 分开表达。
     */
    @Insert("""
            insert into user_solved_problem (user_id, account_id, problem_id, platform,
                                             earliest_known_ac_at, first_ac_confidence, source)
            values (#{userId}, #{accountId}, #{problemId}, #{platform},
                    #{acAt,jdbcType=TIMESTAMP}, #{confidence}, #{source})
            on conflict (account_id, problem_id) do update
               set earliest_known_ac_at = case
                       when excluded.platform = 'LEETCODE_CN'
                            and user_solved_problem.first_ac_confidence = 'USER_REPORTED'
                            and excluded.first_ac_confidence = 'EARLIEST_KNOWN'
                            and user_solved_problem.earliest_known_ac_at <= excluded.earliest_known_ac_at
                       then user_solved_problem.earliest_known_ac_at
                       when user_solved_problem.first_ac_confidence = 'USER_REPORTED'
                            and excluded.earliest_known_ac_at is not null
                            and excluded.first_ac_confidence <> 'USER_REPORTED'
                       then excluded.earliest_known_ac_at
                       else least(
                       coalesce(user_solved_problem.earliest_known_ac_at, excluded.earliest_known_ac_at),
                       coalesce(excluded.earliest_known_ac_at, user_solved_problem.earliest_known_ac_at)) end,
                   first_ac_confidence = case
                       when user_solved_problem.first_ac_confidence = 'EXACT' then 'EXACT'
                       when excluded.platform = 'LEETCODE_CN'
                            and user_solved_problem.first_ac_confidence = 'USER_REPORTED'
                            and excluded.first_ac_confidence = 'EARLIEST_KNOWN'
                            and user_solved_problem.earliest_known_ac_at <= excluded.earliest_known_ac_at
                       then 'USER_REPORTED'
                       when excluded.earliest_known_ac_at is null then user_solved_problem.first_ac_confidence
                       else excluded.first_ac_confidence end
            """)
    int upsert(@Param("userId") Long userId, @Param("accountId") Long accountId,
               @Param("problemId") Long problemId, @Param("platform") String platform,
               @Param("acAt") Instant acAt, @Param("confidence") String confidence,
               @Param("source") String source);

    /**
     * 历史已完整回填后，最早已知通过时间才可视为首次 AC。
     */
    @Update("""
            update user_solved_problem
               set first_ac_confidence = 'EXACT'
             where account_id = #{accountId}
               and earliest_known_ac_at is not null
               and first_ac_confidence <> 'EXACT'
               and first_ac_confidence <> 'USER_REPORTED'
            """)
    int promoteConfidence(@Param("accountId") Long accountId);

    /** 仅给公开通过关系补自报时间；不覆盖源站已确认的时间或新增通过关系。 */
    @Update("""
            update user_solved_problem
               set earliest_known_ac_at = #{acAt}, first_ac_confidence = 'USER_REPORTED'
             where account_id = #{accountId} and problem_id = #{problemId} and source in ('SYNC','BROWSER_SYNC')
               and (earliest_known_ac_at is null or
                    (first_ac_confidence = 'USER_REPORTED' and earliest_known_ac_at > #{acAt}))
            """)
    int recordBrowserTime(@Param("accountId") Long accountId, @Param("problemId") Long problemId,
                          @Param("acAt") Instant acAt);

    /**
     * 历史未完整回填时，首次 AC 时间只能标为“已知最早”，不能声称精确。
     */
    @Update("""
            update user_solved_problem
               set first_ac_confidence = 'EARLIEST_KNOWN'
             where account_id = #{accountId} and first_ac_confidence = 'EXACT'
            """)
    int downgradeConfidence(@Param("accountId") Long accountId);
}
