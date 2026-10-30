package com.szpu.algohub.sync;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 按数据项记录的同步状态。成功时更新数量与成功时间；失败时只改状态与错误，
 * 上次成功的时间与数量原样保留——页面据此区分"没取到""取失败了"和"平台说是零"。
 */
@Mapper
public interface AccountDataStatusMapper {

    String TOTALS = "TOTALS";
    String SOLVED_LIST = "SOLVED_LIST";
    String HISTORY = "HISTORY";
    String RATING = "RATING";

    @Insert("""
            insert into account_data_status (account_id, item, status, last_attempt_at, last_success_at,
                                             error_kind, error_msg, observed_count, detail)
            values (#{accountId}, #{item}, 'SUCCESS', now(), now(), null, null,
                    #{observedCount,jdbcType=INTEGER}, #{detail,jdbcType=VARCHAR})
            on conflict (account_id, item) do update
               set status = 'SUCCESS', last_attempt_at = now(), last_success_at = now(),
                   error_kind = null, error_msg = null,
                   observed_count = excluded.observed_count, detail = excluded.detail
            """)
    int success(@Param("accountId") Long accountId, @Param("item") String item,
                @Param("observedCount") Integer observedCount, @Param("detail") String detail);

    @Insert("""
            insert into account_data_status (account_id, item, status, last_attempt_at, error_kind, error_msg)
            values (#{accountId}, #{item}, #{status}, now(), #{errorKind}, #{errorMsg,jdbcType=VARCHAR})
            on conflict (account_id, item) do update
               set status = excluded.status, last_attempt_at = now(),
                   error_kind = excluded.error_kind, error_msg = excluded.error_msg
            """)
    int failure(@Param("accountId") Long accountId, @Param("item") String item, @Param("status") String status,
                @Param("errorKind") String errorKind, @Param("errorMsg") String errorMsg);

    @Select("""
            select d.account_id as "accountId", d.item, d.status, d.last_attempt_at as "lastAttemptAt",
                   d.last_success_at as "lastSuccessAt", d.error_kind as "errorKind", d.error_msg as "errorMsg",
                   d.observed_count as "observedCount", d.detail
              from account_data_status d
              join platform_account a on a.id = d.account_id
             where a.user_id = #{userId}
             order by d.account_id, d.item
            """)
    List<Map<String, Object>> findByUser(@Param("userId") Long userId);
}
