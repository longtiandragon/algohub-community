package com.szpu.algohub.weekly;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** One shared poller per instance. Never perform source requests on the scheduler or request thread. */
@Component
@ConditionalOnProperty(prefix="algohub.sync",name="enabled",havingValue="true",matchIfMissing=true)
class WeeklyRefreshTimer {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRefreshTimer.class);
    private final WeeklyRefresh refresh;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("weekly-refresh").factory());
    private final AtomicBoolean running=new AtomicBoolean();
    WeeklyRefreshTimer(WeeklyRefresh refresh) { this.refresh=refresh; }

    @Scheduled(fixedDelay=60000,initialDelay=3000)
    void discover() { refresh.discover(); }

    @Scheduled(fixedDelay=3000,initialDelay=4500)
    void tick() {
        if (!running.compareAndSet(false,true)) return;
        worker.submit(() -> {
            try {
                for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) {
                    if (!refresh.next()) break;
                }
            } catch (RuntimeException ex) {
                log.error("训练赛同步队列读取失败", ex);
            } finally { running.set(false); }
        });
    }

    @PreDestroy void stop() { worker.shutdownNow(); }
}

@Service
class WeeklyRefresh {
    private static final Logger log=LoggerFactory.getLogger(WeeklyRefresh.class);
    private final JdbcTemplate db;
    private final WeeklySource source;
    private final ObjectMapper json;
    WeeklyRefresh(JdbcTemplate db, WeeklySource source,ObjectMapper json) {
        this.db=db;
        this.source=source;
        this.json=json;
    }

    void discover() {
        db.update("""
            insert into weekly_account_result(training_id,account_id)
            select t.id,a.id from weekly_training t
            join school_member m on m.school_id=t.school_id and m.status='APPROVED'
              and jsonb_exists(t.targets,m.enrollment_year::text)
            join app_user u on u.id=m.user_id and u.status='ACTIVE' and u.role='STUDENT'
            join platform_account a on a.user_id=u.id and a.platform=t.platform and a.ownership_status='VERIFIED'
            where t.platform in ('CODEFORCES','NOWCODER') or (t.platform='ATCODER' and ?)
            on conflict do nothing
            """,source.atcoderStandingsEnabled());
    }

    private record Job(long training,long account,String meta,String handle,String uid,OffsetDateTime lease) {}

    boolean next() {
        var jobs=db.query("""
            with job as (
                select r.training_id,r.account_id,t.metadata,a.handle,a.platform_uid
                from weekly_account_result r join weekly_training t on t.id=r.training_id
                join platform_account a on a.id=r.account_id and a.ownership_status='VERIFIED' and a.platform=t.platform
                join app_user u on u.id=a.user_id and u.status='ACTIVE' and u.role='STUDENT'
                join school_member m on m.user_id=u.id and m.school_id=t.school_id and m.status='APPROVED'
                    and jsonb_exists(t.targets,m.enrollment_year::text)
                where r.next_attempt<=now() and (t.platform <> 'ATCODER' or ?)
                order by r.next_attempt,r.training_id,r.account_id limit 1 for update of r skip locked
            )
            update weekly_account_result r set next_attempt=now()+interval '10 minutes'
            from job where (r.training_id,r.account_id)=(job.training_id,job.account_id)
            returning r.training_id,r.account_id,job.metadata::text,job.handle,job.platform_uid,r.next_attempt
            """,(rs,i)->new Job(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,OffsetDateTime.class)),source.atcoderStandingsEnabled());
        if(jobs.isEmpty()) return false;
        Job j=jobs.getFirst();
        try {
            var meta=json.readValue(j.meta(),WeeklySource.Meta.class);
            List<WeeklySource.Row> rows=source.results(meta,j.handle(),j.uid());
            // Compare the lease before writing: a slower worker cannot overwrite a newer worker.
            db.update("""
                update weekly_account_result r set result=?::jsonb,failed=false,refreshed_at=now(),
                    next_attempt=now()+case when t.week_start >= current_date-7 then interval '20 minutes' else interval '1 day' end
                from weekly_training t where t.id=r.training_id and r.training_id=? and r.account_id=? and r.next_attempt=?
                """,json.writeValueAsString(rows),j.training(),j.account(),j.lease());
        } catch(Exception ex) {
            db.update("""
                update weekly_account_result set failed=true,next_attempt=now()+interval '10 minutes'
                where training_id=? and account_id=? and next_attempt=?
                """,j.training(),j.account(),j.lease());
            log.warn("训练赛同步失败 training={} account={} type={}",j.training(),j.account(),ex.getClass().getSimpleName());
        }
        return true;
    }
}
