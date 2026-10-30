package com.szpu.algohub.sync;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SyncTaskMapper {

    @Select("""
            select max(created_at) from sync_task
             where task_type = 'PROBLEM_CATALOG' and platform = #{platform} and account_id is null
            """)
    java.time.Instant latestCatalogAttempt(@Param("platform") String platform);

    /**
     * 同一账号的同类任务不重叠：已有 PENDING / RUNNING 时不再入队（由部分唯一索引保证）。
     */
    @Insert("""
            insert into sync_task (task_type, platform, account_id, status, priority, max_attempt, next_run_at)
            values (#{taskType}, #{platform,jdbcType=VARCHAR}, #{accountId,jdbcType=BIGINT},
                    'PENDING', #{priority}, #{maxAttempt}, now())
            on conflict do nothing
            """)
    int enqueue(SyncTask task);

    @Select("""
            select count(*) from sync_task
             where task_type = #{taskType} and account_id is null and status in ('PENDING', 'RUNNING')
            """)
    int countActiveGlobal(@Param("taskType") String taskType);

    @Select("""
            select count(*) from sync_task
             where task_type = #{taskType}
               and platform = #{platform}
               and account_id is null
               and status in ('PENDING', 'RUNNING')
            """)
    int countActiveByTypeAndPlatform(@Param("taskType") String taskType, @Param("platform") String platform);

    /**
     * 原子领取任务：取最高优先级的可执行任务，写入执行租约与新的执行编号。
     * 租约过期的 RUNNING 任务允许被其他 worker 接管（进程重启后据此恢复），接管后执行编号随之更换，
     * 旧执行的保存会因编号不符被拒绝。
     * 冷却中的平台（刚被限流）暂不领取；同一平台正在执行的任务达到上限时也先领别的平台。
     */
    @Select("""
            update sync_task
               set status = 'RUNNING',
                   lease_owner = #{owner},
                   run_token = #{runToken},
                   lease_until = now() + (#{leaseSeconds} * interval '1 second'),
                   heartbeat_at = now(),
                   attempt = attempt + 1,
                   updated_at = now()
             where id = (
                   select t.id from sync_task t
                    where ((t.status = 'PENDING' and t.next_run_at <= now())
                           or (t.status = 'RUNNING' and t.lease_until < now()))
                      and not exists (select 1 from platform_throttle pt
                                       where pt.platform = t.platform and pt.blocked_until > now())
                      and (t.platform is null
                           or (select count(*) from sync_task r
                                where r.platform = t.platform and r.status = 'RUNNING'
                                  and r.lease_until >= now() and r.id <> t.id) < #{perPlatform})
                    order by t.priority, t.next_run_at
                    limit 1
                    for update skip locked)
            returning *
            """)
    SyncTask claimNext(@Param("owner") String owner, @Param("runToken") String runToken,
                       @Param("leaseSeconds") long leaseSeconds, @Param("perPlatform") int perPlatform);

    /**
     * 在当前事务里锁住任务行，并确认仍由这次执行持有。返回 null 表示已被接管、已结束或已随账号删除。
     * 保存结果的事务都先调用它：锁住后别的 worker 无法接管（领取时 skip locked），接管完成后编号已变。
     */
    @Select("""
            select id from sync_task
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
               for update
            """)
    Long lockOwned(@Param("id") Long id, @Param("runToken") String runToken);

    /** 续租：只延长这次执行自己的租约。返回 0 表示已被接管或已结束。 */
    @Update("""
            update sync_task
               set lease_until = now() + (#{leaseSeconds} * interval '1 second'),
                   heartbeat_at = now()
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
            """)
    int renewLease(@Param("id") Long id, @Param("runToken") String runToken,
                   @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            update sync_task
               set status = 'SUCCESS', error_kind = null, error_msg = null,
                   duration_ms = #{durationMs}, finished_at = now(), updated_at = now(),
                   lease_owner = null, lease_until = null
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
            """)
    int markSuccess(@Param("id") Long id, @Param("runToken") String runToken, @Param("durationMs") long durationMs);

    @Update("""
            update sync_task set status = 'PENDING', attempt = 0,
                   next_run_at = now() + interval '2 seconds',
                   lease_owner = null, lease_until = null, finished_at = null,
                   error_kind = null, error_msg = null, duration_ms = #{durationMs}, updated_at = now()
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
            """)
    int continueLater(@Param("id") Long id, @Param("runToken") String runToken, @Param("durationMs") long durationMs);

    /** 放回队列：本次执行的数据作废但不算失败（例如账号标识在抓取期间变了），稍后按新状态重跑。 */
    @Update("""
            update sync_task
               set status = 'PENDING', next_run_at = now() + interval '2 seconds',
                   lease_owner = null, lease_until = null, updated_at = now()
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
            """)
    int release(@Param("id") Long id, @Param("runToken") String runToken);

    /** 有限重试并退避；不可重试的错误直接置为失败，等待人工处理。 */
    @Update("""
            update sync_task
               set status = case when #{retryable} and attempt < max_attempt then 'PENDING' else 'FAILED' end,
                   next_run_at = now() + (#{backoffSeconds} * interval '1 second'),
                   error_kind = #{errorKind}, error_msg = #{errorMsg},
                   duration_ms = #{durationMs}, updated_at = now(),
                   finished_at = case when #{retryable} and attempt < max_attempt then null else now() end,
                   lease_owner = null, lease_until = null
             where id = #{id} and run_token = #{runToken} and status = 'RUNNING'
            """)
    int markFailure(@Param("id") Long id, @Param("runToken") String runToken, @Param("errorKind") String errorKind,
                    @Param("errorMsg") String errorMsg, @Param("retryable") boolean retryable,
                    @Param("backoffSeconds") long backoffSeconds, @Param("durationMs") long durationMs);

    @Select("""
            select t.*, a.handle, u.username
              from sync_task t
              left join platform_account a on a.id = t.account_id
              left join app_user u on u.id = a.user_id
             where t.platform is distinct from 'LANQIAO'
             order by t.updated_at desc
             limit #{limit}
            """)
    List<SyncTask> recent(@Param("limit") int limit);

    @Select("""
            select t.*, a.handle, u.username
              from sync_task t
              left join platform_account a on a.id = t.account_id
              left join app_user u on u.id = a.user_id
             where a.user_id = #{userId} and t.platform <> 'LANQIAO'
             order by t.updated_at desc
             limit #{limit}
            """)
    List<SyncTask> recentForUser(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("select count(*) from sync_task where status = #{status} and platform is distinct from 'LANQIAO'")
    int countByStatus(@Param("status") String status);

    @Select("select * from sync_task where id = #{id}")
    SyncTask findById(@Param("id") Long id);
}
