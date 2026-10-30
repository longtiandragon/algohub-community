package com.szpu.algohub.contest;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContestMapper {

    /** 按平台比赛 ID 去重；延期、取消与时间变更以最新抓取为准。 */
    @Insert("""
            insert into contest (platform, contest_key, title, start_at, end_at, duration_sec,
                                 status, category, url, source, updated_at)
            values (#{platform}, #{contestKey}, #{title}, #{startAt}, #{endAt,jdbcType=TIMESTAMP},
                    #{durationSec,jdbcType=INTEGER}, #{status}, #{category,jdbcType=VARCHAR},
                    #{url,jdbcType=VARCHAR}, #{source}, now())
            on conflict (platform, contest_key) do update
               set title = excluded.title,
                   start_at = excluded.start_at,
                   end_at = excluded.end_at,
                   duration_sec = excluded.duration_sec,
                   status = excluded.status,
                   category = coalesce(contest.category, excluded.category),
                   url = excluded.url,
                   source = excluded.source,
                   updated_at = now()
            """)
    int upsert(Contest contest);

    @Select("""
            select * from contest
             where start_at >= #{from}
               and (#{platform,jdbcType=VARCHAR} is null or platform = #{platform,jdbcType=VARCHAR})
               and (#{category,jdbcType=VARCHAR} is null or category like '%' || #{category,jdbcType=VARCHAR} || '%')
             order by start_at
             limit #{limit}
            """)
    List<Contest> findUpcoming(@Param("from") Instant from, @Param("platform") String platform,
                               @Param("category") String category, @Param("limit") int limit);

    @Select("select distinct category from contest where category is not null order by category")
    List<String> listCategories();

    @Select("select max(updated_at) from contest")
    Instant lastUpdatedAt();

    /** 清理很久以前结束的比赛，日历只保留近期与未来场次。 */
    @Delete("delete from contest where start_at < #{before}")
    int deleteOlderThan(@Param("before") Instant before);

    // ---------- 比赛收藏 ----------

    @Insert("""
            insert into contest_bookmark (user_id, contest_id) values (#{userId}, #{contestId})
            on conflict do nothing
            """)
    int favorite(@Param("userId") Long userId, @Param("contestId") Long contestId);

    @Delete("delete from contest_bookmark where user_id = #{userId} and contest_id = #{contestId}")
    int unfavorite(@Param("userId") Long userId, @Param("contestId") Long contestId);

    @Select("select count(*) from contest where id = #{id}")
    int exists(@Param("id") Long id);

    /** 我收藏的比赛 ID，用于给列表打标记。 */
    @Select("select contest_id from contest_bookmark where user_id = #{userId}")
    List<Long> favoritedIds(@Param("userId") Long userId);

    /**
     * 我收藏的比赛，按开始时间排序。
     * 比赛信息全部实时取自 contest 表：源站改期、改名或取消后这里跟着变，
     * 不会留下一份对不上的旧副本。
     */
    @Select("""
            select c.* from contest c
              join contest_bookmark b on b.contest_id = c.id and b.user_id = #{userId}
             order by c.start_at
            """)
    List<Contest> findFavorited(@Param("userId") Long userId);

    /** 管理员可人工修正分类，覆盖名称规则的判断结果。 */
    @Update("update contest set category = #{category}, updated_at = now() where id = #{id}")
    int updateCategory(@Param("id") Long id, @Param("category") String category);
}
