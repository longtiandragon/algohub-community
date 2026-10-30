package com.szpu.algohub.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 比赛与 Rating 记录：按账号 + 比赛唯一，重复同步只更新。 */
@Mapper
public interface ContestParticipationMapper {

    @Insert("""
            insert into contest_participation (account_id, contest_key, contest_name, place, old_rating, new_rating,
                                               performance, rated, finished_at)
            values (#{accountId}, #{contestKey}, #{contestName}, #{place,jdbcType=INTEGER},
                    #{oldRating,jdbcType=INTEGER}, #{newRating,jdbcType=INTEGER}, #{performance,jdbcType=INTEGER},
                    #{rated}, #{finishedAt})
            on conflict (account_id, contest_key) do update
               set contest_name = excluded.contest_name, place = excluded.place,
                   old_rating = excluded.old_rating, new_rating = excluded.new_rating,
                   performance = excluded.performance, rated = excluded.rated,
                   finished_at = excluded.finished_at, updated_at = now()
            """)
    int upsert(@Param("accountId") Long accountId, @Param("contestKey") String contestKey,
               @Param("contestName") String contestName, @Param("place") Integer place,
               @Param("oldRating") Integer oldRating, @Param("newRating") Integer newRating,
               @Param("performance") Integer performance, @Param("rated") boolean rated,
               @Param("finishedAt") Instant finishedAt);

    /** 每个账号：当前 Rating（最近一场计分比赛后）、最高 Rating、计分场次与全部场次。 */
    @Select("""
            select c.account_id as "accountId",
                   (select l.new_rating from contest_participation l
                     where l.account_id = c.account_id and l.rated
                     order by l.finished_at desc limit 1) as "currentRating",
                   max(c.new_rating) filter (where c.rated) as "maxRating",
                   count(*) filter (where c.rated) as "ratedContests",
                   count(*) as "contests"
              from contest_participation c
              join platform_account a on a.id = c.account_id
             where a.user_id = #{userId}
             group by c.account_id
            """)
    List<Map<String, Object>> summaryByUser(@Param("userId") Long userId);

    /** 本人各账号的全部参赛记录，按时间先后。 */
    @Select("""
            select a.id as "accountId", a.platform, a.handle, a.ownership_status as "ownershipStatus",
                   c.contest_key as "contestKey", c.contest_name as "contestName", c.place,
                   c.old_rating as "oldRating", c.new_rating as "newRating", c.performance, c.rated,
                   c.finished_at as "finishedAt"
              from contest_participation c
              join platform_account a on a.id = c.account_id
             where a.user_id = #{userId}
             order by c.finished_at, c.contest_key
            """)
    List<Map<String, Object>> historyByUser(@Param("userId") Long userId);
}
