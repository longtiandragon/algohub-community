package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.sync.SyncService;
import com.szpu.algohub.sync.SyncTaskMapper;
import com.szpu.algohub.sync.SyncTask;

@SpringBootTest
@Transactional
class HduHistoryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncService sync;
    @Autowired SyncTaskMapper tasks;
    @MockitoBean PlatformHttpClient http;

    @Test
    void snapshotUpgradeBackfillsDatesAndKeepsRetryIdempotent() throws Exception {
        String html;
        try (var in = getClass().getResourceAsStream("/fixtures/hdu-status.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        when(http.get(eq(Platform.HDU), anyString(), eq(Map.of()), isNull(), any(Charset.class)))
                .thenReturn(html);
        Long userId = jdbc.queryForObject("""
                insert into app_user(username,password_hash,display_name,role,status)
                values ('hdu-integration','x','杭电测试','STUDENT','ACTIVE') returning id
                """, Long.class);
        Long accountId = jdbc.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,
                                             auth_mode,sync_status,supports_history)
                values (?,'HDU','sample_user_hdu','sample_user_hdu','RESOLVED','PUBLIC','IDLE',true) returning id
                """, Long.class, userId);
        jdbc.update("insert into total_snapshot(account_id,solved_total,submission_total,source) values (?,1,4,'SYNC')", accountId);
        jdbc.update("""
                insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source)
                values (?,?,'HDU',current_date,'OBSERVED_DELTA',1,'SNAPSHOT_DELTA')
                """, userId, accountId);
        assertThat(com.szpu.algohub.sync.SyncTestTasks.syncOnce(jdbc, tasks, sync, accountId)).isFalse();
        assertThat(com.szpu.algohub.sync.SyncTestTasks.syncOnce(jdbc, tasks, sync, accountId)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?", Integer.class, accountId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?", Boolean.class, accountId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from total_snapshot where account_id=?", Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from daily_stat where account_id=? and metric='OBSERVED_DELTA'", Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("select value from daily_stat where account_id=? and metric='FIRST_AC' and stat_date='2026-07-23'", Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select value from daily_stat where account_id=? and metric='SUBMISSIONS' and stat_date='2026-07-23'", Integer.class, accountId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select value from daily_stat where account_id=? and metric='SUBMISSIONS' and stat_date='2026-07-28'", Integer.class, accountId)).isEqualTo(1);

        var task = new SyncTask();
        task.setAccountId(accountId);
        task.setPlatform("HDU");
        task.setTaskType("HISTORY_BACKFILL");
        task.setMaxAttempt(5);
        tasks.enqueue(task);
        Long taskId = jdbc.queryForObject("select id from sync_task where account_id=? and task_type='HISTORY_BACKFILL'",
                Long.class, accountId);
        jdbc.update("update sync_task set status='RUNNING',attempt=4,run_token='backfill-run' where id=?", taskId);
        tasks.continueLater(taskId, "backfill-run", 25);
        assertThat(tasks.findById(taskId).getStatus()).isEqualTo("PENDING");
        assertThat(tasks.findById(taskId).getAttempt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sync_task where account_id=? and task_type='HISTORY_BACKFILL'",
                Integer.class, accountId)).isEqualTo(1);
    }
}
