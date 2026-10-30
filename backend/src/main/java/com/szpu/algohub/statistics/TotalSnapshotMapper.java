package com.szpu.algohub.statistics;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TotalSnapshotMapper {

    @Insert("""
            insert into total_snapshot (account_id, solved_total, submission_total, source, raw_extra)
            values (#{accountId}, #{solvedTotal,jdbcType=INTEGER}, #{submissionTotal,jdbcType=INTEGER},
                    #{source}, #{rawExtra,jdbcType=VARCHAR})
            """)
    int insert(@Param("accountId") Long accountId, @Param("solvedTotal") Integer solvedTotal,
               @Param("submissionTotal") Integer submissionTotal, @Param("source") String source,
               @Param("rawExtra") String rawExtra);

    @Select("""
            select solved_total as "solvedTotal", submission_total as "submissionTotal",
                   captured_at as "capturedAt", raw_extra as "rawExtra"
              from total_snapshot
             where account_id = #{accountId}
             order by captured_at desc
             limit 1
            """)
    Map<String, Object> latest(@Param("accountId") Long accountId);

    @Select("""
            select account_id as "accountId", solved_total as "solvedTotal",
                   submission_total as "submissionTotal", captured_at as "capturedAt"
              from total_snapshot
             where account_id = #{accountId}
             order by captured_at desc
             limit #{limit}
            """)
    List<Map<String, Object>> history(@Param("accountId") Long accountId, @Param("limit") int limit);
}
