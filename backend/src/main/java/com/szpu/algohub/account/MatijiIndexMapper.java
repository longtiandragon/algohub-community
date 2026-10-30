package com.szpu.algohub.account;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 码蹄集昵称索引：排行榜上看到过的账号（UID、昵称、学校、题数、看到的时间）。 */
@Mapper
public interface MatijiIndexMapper {

    @Insert("""
            insert into matiji_user_index (uid, nickname, school, pass_num, rank_order, observed_at)
            values (#{uid}, #{nickname}, #{school,jdbcType=VARCHAR}, #{passNum}, #{rankOrder}, now())
            on conflict (uid) do update
               set nickname = excluded.nickname, school = excluded.school, pass_num = excluded.pass_num,
                   rank_order = excluded.rank_order, observed_at = now()
            """)
    int upsert(@Param("uid") String uid, @Param("nickname") String nickname, @Param("school") String school,
               @Param("passNum") int passNum, @Param("rankOrder") int rankOrder);

    /** 同名的全部候选。昵称可能重复，由同学核对学校与题数后自己选。 */
    @Select("""
            select uid as "userId", nickname, school, pass_num as "passNum", rank_order as "rankOrder",
                   observed_at as "observedAt"
              from matiji_user_index
             where nickname = #{nickname}
             order by rank_order, uid
             limit 50
            """)
    List<Map<String, Object>> findByNickname(@Param("nickname") String nickname);

    @Select("select count(*) from matiji_user_index")
    int count();

    @Select("select next_start as \"nextStart\", total, round_started as \"roundStarted\", last_run_at as \"lastRunAt\", last_error as \"lastError\" from matiji_index_state where id = 1")
    Map<String, Object> state();

    @Update("""
            update matiji_index_state
               set next_start = #{nextStart}, total = #{total,jdbcType=INTEGER},
                   round_started = case when #{newRound} then now() else round_started end,
                   last_run_at = now(), last_error = #{lastError,jdbcType=VARCHAR}
             where id = 1
            """)
    int saveState(@Param("nextStart") int nextStart, @Param("total") Integer total,
                  @Param("newRound") boolean newRound, @Param("lastError") String lastError);
}
