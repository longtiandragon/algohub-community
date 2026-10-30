package com.szpu.algohub.sync;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SyncCursorMapper {

    @Select("""
            select cursor_value from sync_cursor
             where account_id = #{accountId} and cursor_kind = #{kind}
            """)
    String findValue(@Param("accountId") Long accountId, @Param("kind") String kind);

    @Select("""
            select completed from sync_cursor
             where account_id = #{accountId} and cursor_kind = #{kind}
            """)
    Boolean findCompleted(@Param("accountId") Long accountId, @Param("kind") String kind);

    /** 成功入库后才推进游标。 */
    @Insert("""
            insert into sync_cursor (account_id, cursor_kind, cursor_value, covered_to, completed, updated_at)
            values (#{accountId}, #{kind}, #{value,jdbcType=VARCHAR}, #{coveredTo,jdbcType=TIMESTAMP},
                    #{completed}, now())
            on conflict (account_id, cursor_kind) do update
               set cursor_value = excluded.cursor_value,
                   covered_to = coalesce(excluded.covered_to, sync_cursor.covered_to),
                   completed = sync_cursor.completed or excluded.completed,
                   updated_at = now()
            """)
    int save(@Param("accountId") Long accountId, @Param("kind") String kind,
             @Param("value") String value, @Param("coveredTo") Instant coveredTo,
             @Param("completed") boolean completed);
}
