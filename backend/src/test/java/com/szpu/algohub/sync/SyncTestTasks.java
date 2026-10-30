package com.szpu.algohub.sync;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试用：同步的每次写入都要核对执行编号，所以直接调 syncAccount 之前要先"领取"一个任务。
 * 这里绕过全局优先级，直接领取指定账号的任务，便于只测同步逻辑本身。
 */
public final class SyncTestTasks {

    private SyncTestTasks() {
    }

    /** 为账号准备一个由本次执行持有的 ACCOUNT_SYNC 任务（已有待执行的就领取它），返回带执行编号的任务。 */
    public static SyncTask claim(JdbcTemplate jdbc, SyncTaskMapper tasks, Long accountId) {
        String platform = jdbc.queryForObject("select platform from platform_account where id = ?", String.class,
                accountId);
        jdbc.update("""
                insert into sync_task (task_type, platform, account_id, status, priority, max_attempt)
                values ('ACCOUNT_SYNC', ?, ?, 'PENDING', 100, 5)
                on conflict do nothing
                """, platform, accountId);
        String token = UUID.randomUUID().toString();
        Long id = jdbc.queryForObject("""
                update sync_task
                   set status = 'RUNNING', run_token = ?, lease_owner = 'test',
                       lease_until = now() + interval '10 minutes', attempt = attempt + 1
                 where account_id = ? and task_type = 'ACCOUNT_SYNC' and status in ('PENDING', 'RUNNING')
                returning id
                """, Long.class, token, accountId);
        return tasks.findById(id);
    }

    /** 领取并同步一次，返回是否还有后续页。 */
    public static boolean syncOnce(JdbcTemplate jdbc, SyncTaskMapper tasks, SyncService sync, Long accountId) {
        return sync.syncAccount(claim(jdbc, tasks, accountId));
    }
}
