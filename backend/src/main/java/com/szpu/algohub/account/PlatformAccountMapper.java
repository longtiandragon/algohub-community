package com.szpu.algohub.account;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformAccountMapper {

    String SELECT_WITH_FLAGS = """
            select a.*, u.username, u.display_name,
                   exists(select 1 from platform_credential c where c.account_id = a.id
                          and c.status = 'ACTIVE') as has_credential,
                   (select c.status from platform_credential c where c.account_id = a.id) as credential_status
              from platform_account a
              join app_user u on u.id = a.user_id
            """;

    @Select(SELECT_WITH_FLAGS + " where a.id = #{id}")
    PlatformAccount findById(@Param("id") Long id);

    @Select(SELECT_WITH_FLAGS + " where a.user_id = #{userId} order by a.platform")
    List<PlatformAccount> findByUser(@Param("userId") Long userId);

    /**
     * 同步保存前锁住账号行，核对抓取期间账号没被解绑、标识没变。
     * 锁顺序固定为"账号行 → 任务行"，与解绑时级联删除任务的顺序一致，避免死锁。
     */
    @Select("select id, platform_uid, handle from platform_account where id = #{id} for update")
    PlatformAccount lockForSync(@Param("id") Long id);

    @Select(SELECT_WITH_FLAGS + " order by a.updated_at desc")
    List<PlatformAccount> findAll();

    /** 每个账号已收录的题目明细：去重通过题数、其中有通过时间的、其中来自牛客 Tracker 的。 */
    @Select("""
            select s.account_id as "accountId", count(*) as collected,
                   count(*) filter (where s.earliest_known_ac_at is not null) as timed,
                   count(*) filter (where s.source = 'TRACKER') as tracker
              from user_solved_problem s
             where s.user_id = #{userId}
             group by s.account_id
            """)
    List<java.util.Map<String, Object>> solvedCounts(@Param("userId") Long userId);

    /**
     * 牛客 Tracker 的去重情况：Tracker 通过了多少道、其中多少道与竞赛题是同一题（已合并，不重复计）、
     * 多少道题号撞上了但标题不同（另记，不合并）。
     */
    @Select(NowcoderTrackerMapping.MAPPED + """
            select m.account_id as "accountId", count(*) as accepted,
                   count(*) filter (where m.problem_key = m.pid and s.source is not null and s.source <> 'TRACKER') as merged,
                   count(*) filter (where m.problem_key <> m.pid) as conflicts
              from mapped m
              join platform_account a on a.id = m.account_id
              left join problem p on p.platform = 'NOWCODER' and p.problem_key = m.problem_key
              left join user_solved_problem s on s.account_id = m.account_id and s.problem_id = p.id
             where a.user_id = #{userId}
             group by m.account_id
            """)
    List<java.util.Map<String, Object>> trackerBreakdown(@Param("userId") Long userId);

    /** 每个账号最近一次给出了题数的平台快照。从没取得过就没有这一行（页面显示"尚未取得"，不是 0）。 */
    @Select("""
            select distinct on (t.account_id) t.account_id as "accountId", t.solved_total as "solvedTotal",
                   t.captured_at as "capturedAt"
              from total_snapshot t
              join platform_account a on a.id = t.account_id
             where a.user_id = #{userId} and t.solved_total is not null
             order by t.account_id, t.captured_at desc, t.id desc
            """)
    List<java.util.Map<String, Object>> latestTotals(@Param("userId") Long userId);

    @Select("select * from platform_account where user_id=#{userId} and platform=#{platform} and platform_uid=#{uid}")
    PlatformAccount findMineByUid(@Param("userId") Long userId, @Param("platform") String platform, @Param("uid") String uid);

    /** 已验证的归属者。部分唯一索引保证同一平台 UID 至多一行 VERIFIED。 */
    @Select("select * from platform_account where platform = #{platform} and platform_uid = #{uid} and ownership_status = 'VERIFIED'")
    PlatformAccount findByPlatformUid(@Param("platform") String platform, @Param("uid") String uid);

    /** 同上，并锁住该行，供申诉审核在同一事务内改归属。 */
    @Select("""
            select * from platform_account
             where platform = #{platform} and platform_uid = #{uid} and ownership_status = 'VERIFIED'
               for update
            """)
    PlatformAccount lockVerifiedOwner(@Param("platform") String platform, @Param("uid") String uid);

    /** 归属证明通过：只改归属标记与展示名，账号 id、凭据、刷题记录都不动。 */
    @Update("""
            update platform_account
               set ownership_status = 'VERIFIED',
                   handle = #{handle},
                   ownership_note = null,
                   updated_at = now()
             where id = #{id} and user_id = #{userId}
            """)
    int markVerified(@Param("id") Long id, @Param("userId") Long userId, @Param("handle") String handle);

    /** 申诉审核通过后撤下原归属者的标记：账号转为个人关注，记录与凭据保留。 */
    @Update("""
            update platform_account
               set ownership_status = 'RESOLVED',
                   ownership_note = #{note},
                   updated_at = now()
             where id = #{id} and ownership_status = 'VERIFIED'
            """)
    int revokeVerified(@Param("id") Long id, @Param("note") String note);

    @Select("""
            select a.* from platform_account a
             where a.sync_status <> 'PAUSED'
               and a.auth_mode <> 'BROWSER'
               and not exists (select 1 from sync_task t
                                where t.account_id = a.id
                                  and t.task_type = 'ACCOUNT_SYNC'
                                  and t.status in ('PENDING','RUNNING'))
               and (a.last_attempt_at is null or a.last_attempt_at < #{before})
            """)
    List<PlatformAccount> findDueForSync(@Param("before") Instant before);

    @Insert("""
            insert into platform_account (user_id, platform, platform_uid, handle, ownership_status,
                                          auth_mode, sync_status, supports_history, note)
            values (#{userId}, #{platform}, #{platformUid,jdbcType=VARCHAR}, #{handle}, #{ownershipStatus},
                    #{authMode}, #{syncStatus}, #{supportsHistory}, #{note,jdbcType=VARCHAR})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PlatformAccount account);

    /** 凭据/公开资料更新不改变归属，避免旧快照覆盖并发核验或申诉审核的结果。 */
    @Update("""
            update platform_account
               set handle = #{handle},
                   platform_uid = #{platformUid,jdbcType=VARCHAR},
                   auth_mode = #{authMode},
                   updated_at = now()
             where id = #{id}
            """)
    int updateIdentity(PlatformAccount account);

    @Update("""
            update platform_account
               set sync_status = #{status},
                   last_attempt_at = now(),
                   updated_at = now()
             where id = #{id}
            """)
    int markAttempt(@Param("id") Long id, @Param("status") String status);

    @Update("""
            update platform_account
               set sync_status = 'OK',
                   last_success_at = now(),
                   last_attempt_at = now(),
                   last_error_kind = null,
                   last_error_msg = null,
                   history_complete = #{historyComplete},
                   updated_at = now()
             where id = #{id}
            """)
    int markSuccess(@Param("id") Long id, @Param("historyComplete") boolean historyComplete);

    /** 快照或浏览器导入不会撤销抓取期间其他事务已确认的历史完整性。 */
    @Update("""
            update platform_account
               set sync_status = 'OK', last_success_at = now(), last_attempt_at = now(),
                   last_error_kind = null, last_error_msg = null,
                   history_complete = history_complete or #{historyComplete}, updated_at = now()
             where id = #{id}
            """)
    int markSnapshotSuccess(@Param("id") Long id, @Param("historyComplete") boolean historyComplete);

    @Update("""
            update platform_account
               set sync_status = #{status},
                   last_attempt_at = now(),
                   last_error_kind = #{errorKind},
                   last_error_msg = #{errorMsg},
                   updated_at = now()
             where id = #{id}
            """)
    int markFailure(@Param("id") Long id, @Param("status") String status,
                    @Param("errorKind") String errorKind, @Param("errorMsg") String errorMsg);

    /**
     * 部分成功：本轮已有数据项写入成功（成功时间已由那次保存更新），另一项失败。
     * 记下失败原因，但不改成功时间与历史完整标记。
     */
    @Update("""
            update platform_account
               set sync_status = 'PARTIAL',
                   last_attempt_at = now(),
                   last_error_kind = #{errorKind},
                   last_error_msg = #{errorMsg},
                   updated_at = now()
             where id = #{id}
            """)
    int markPartial(@Param("id") Long id, @Param("errorKind") String errorKind, @Param("errorMsg") String errorMsg);

    @Update("update platform_account set supports_history=#{supported} where id=#{id}")
    int updateHistorySupport(@Param("id") Long id, @Param("supported") boolean supported);

    @Delete("delete from platform_account where id = #{id}")
    int delete(@Param("id") Long id);

    @Insert("""
            insert into account_identity_history (account_id, old_handle, new_handle)
            values (#{accountId}, #{oldHandle}, #{newHandle})
            """)
    int insertIdentityHistory(@Param("accountId") Long accountId, @Param("oldHandle") String oldHandle,
                              @Param("newHandle") String newHandle);
}
