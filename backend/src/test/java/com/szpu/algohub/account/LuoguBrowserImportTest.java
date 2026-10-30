package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.szpu.algohub.account.LuoguBrowserImportService.BrowserRecord;
import com.szpu.algohub.account.LuoguBrowserImportService.Page;
import com.szpu.algohub.common.ApiException;

@SpringBootTest
class LuoguBrowserImportTest {
    @Autowired LuoguBrowserImportService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.szpu.algohub.statistics.SubmissionMapper submissions;
    @Autowired com.szpu.algohub.statistics.SolvedMapper solved;
    @Autowired PlatformAccountMapper accounts;
    @Autowired PlatformTransactionManager transactionManager;
    private Long user;
    private Long account;

    @BeforeEach void setup() {
        user = jdbc.queryForObject("""
                insert into app_user(username,password_hash,display_name,role)
                values (?,'x','browser-import','STUDENT') returning id
                """, Long.class, "browser-" + UUID.randomUUID());
        account = jdbc.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status)
                values (?,'LUOGU','730187',?,'VERIFIED') returning id
                """, Long.class, user, "browser-" + UUID.randomUUID());
        for (String pid : List.of("P1001", "P1002")) {
            jdbc.update("insert into problem(platform,problem_key) values ('LUOGU',?) on conflict do nothing", pid);
            Long problem = jdbc.queryForObject("select id from problem where platform='LUOGU' and problem_key=?", Long.class, pid);
            jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,source) values (?,?,?,'LUOGU','SYNC')", user, account, problem);
        }
    }

    @AfterEach void cleanup() {
        jdbc.update("delete from platform_account where id=?", account);
        jdbc.update("delete from app_user where id=?", user);
    }

    @Test void importsOnlyAfterAllPagesAndKeepsBrowserTimesUnverified() {
        String ticket = service.begin(user, account).get("ticket");
        assertThat(ticket).matches("730187\\.[A-Za-z0-9_-]{43}");
        long yesterday = Instant.now().minusSeconds(86400).getEpochSecond();
        BrowserRecord one = new BrowserRecord("910000001", "730187", "P1001", "A+B", 0, yesterday, "C++");
        BrowserRecord two = new BrowserRecord("910000002", "730187", "P1002", "过河卒", 2, yesterday - 86400, "C++");
        assertThatThrownBy(() -> service.pageWithTicket(ticket,
                new Page(1, 2, 1, List.of(new BrowserRecord(one.id(), "wrong", one.pid(), one.title(),
                        one.difficulty(), one.submitTime(), one.language()))))).isInstanceOf(ApiException.class);
        assertThat(service.pageWithTicket(ticket, new Page(1, 2, 1, List.of(one))).complete()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?", Integer.class, account)).isZero();
        assertThat(service.pageWithTicket(ticket, new Page(1, 2, 1, List.of(one))).received()).isEqualTo(1);
        assertThat(service.pageWithTicket(ticket, new Page(2, 2, 1, List.of(two))).complete()).isTrue();
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?", Boolean.class, account)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?", Integer.class, account)).isEqualTo(2);
        assertThat(jdbc.queryForList("select time_precision from submission where account_id=?", String.class, account))
                .containsOnly("USER_REPORTED");
        assertThat(jdbc.queryForList("select first_ac_confidence from user_solved_problem where account_id=?", String.class, account))
                .containsOnly("USER_REPORTED");
        assertThat(jdbc.queryForObject("select count(*) from daily_stat where account_id=? and source='SUBMISSION'",
                Long.class, account)).isGreaterThan(0);
        assertThatThrownBy(() -> service.pageWithTicket(ticket, new Page(3, 2, 1, List.of())))
                .isInstanceOf(ApiException.class);
    }

    @Test void mergedUploadBatchesPreserveEverySubmissionAndEarliestAc() {
        String ticket = service.begin(user, account).get("ticket");
        long newest = Instant.now().minusSeconds(86400).getEpochSecond();
        var rows = java.util.stream.IntStream.range(0,501).mapToObj(i ->
                new BrowserRecord(String.valueOf(940000000+i),"730187","P1001","A+B",0,newest-i,"C++")).toList();
        var first=service.pageWithTicket(ticket,new Page(1,501,500,rows.subList(0,500)));
        assertThat(first.complete()).isFalse();
        assertThat(first.received()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,account)).isZero();
        var last=service.pageWithTicket(ticket,new Page(2,501,500,rows.subList(500,501)));
        assertThat(last.complete()).isTrue();
        assertThat(last.received()).isEqualTo(501);
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,account)).isEqualTo(501);
        assertThat(jdbc.queryForObject("select extract(epoch from min(earliest_known_ac_at))::bigint from user_solved_problem where account_id=?",Long.class,account)).isEqualTo(newest-500);
    }

    @Test void browserOnlyProblemsJoinFormalCountsAndAutoGrantCanBeRevoked() {
        String ticket = service.begin(user, account, true).get("ticket");
        assertThat(service.autoStatus(user, account).get("enabled")).isEqualTo(true);
        long yesterday = Instant.now().minusSeconds(86400).getEpochSecond();
        String extraKey = "T" + (2000000 + user);
        var rows = List.of(
                new BrowserRecord("920000001", "730187", "P1001", "A+B", 0, yesterday, "C++"),
                new BrowserRecord("920000002", "730187", extraKey, "比赛题", null, yesterday, "C++"));
        var completed = service.pageWithTicket(ticket, new Page(1, 2, 2, rows));
        assertThat(completed.complete()).isTrue();
        assertThat(completed.autoEnabled()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?", Integer.class, account)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=? and earliest_known_ac_at is not null", Integer.class, account)).isEqualTo(2);
        String again = service.begin(user, account, true).get("ticket");
        service.pageWithTicket(again, new Page(1, 2, 2, rows));
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?", Integer.class, account)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?", Integer.class, account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select difficulty_source from problem where platform='LUOGU' and problem_key=?", String.class, extraKey)).isEqualTo("USER_REPORTED");
        assertThat(jdbc.queryForObject("select difficulty_label from problem where platform='LUOGU' and problem_key=?", String.class, extraKey)).isNull();

        assertThat(service.autoBegin(again).get("uid")).isEqualTo("730187");
        service.revokeAuto(user, account);
        assertThat(service.autoStatus(user, account).get("enabled")).isEqualTo(false);
        assertThatThrownBy(() -> service.autoBegin(again)).isInstanceOf(ApiException.class);
    }

    @Autowired com.szpu.algohub.statistics.CampusRankingService ranking;
    @Autowired com.szpu.algohub.statistics.HistoryMapper history;
    @Autowired com.szpu.algohub.statistics.DailyStatMapper daily;
    @Autowired com.szpu.algohub.activity.ActivityMapper activity;
    @Autowired com.szpu.algohub.statistics.StatisticsService statistics;

    @Test void archivedExtrasBackfillIntoOwnPublicRecordsAndDatedRankingWithoutDuplicatingPublicProblems() {
        String key = "T" + (1000000 + user);
        jdbc.update("insert into luogu_extra_solve(account_id,problem_key,title,ac_at) values (?,?,'archive',now()-interval '1 day')", account, key);
        jdbc.update("insert into luogu_extra_solve(account_id,problem_key,title,ac_at) values (?,'P1001','reported title',now()-interval '1 day')", account);
        jdbc.update("insert into luogu_extra_solve(account_id,problem_key,title) values (?,?,'untimed')", account, key + "0");
        var script = new org.springframework.core.io.ClassPathResource("db/migration/V49__luogu_extra_formal_solves.sql");
        var runner = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(script);
        runner.execute(jdbc.getDataSource());
        runner.execute(jdbc.getDataSource()); // Replay must be idempotent, preserving the public problem relation.
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?", Integer.class, account)).isEqualTo(4);
        assertThat(history.rows(user, "LUOGU", true, null, null, 100, 0)).extracting(row -> row.get("problemKey")).contains(key, key + "0");
        assertThat(history.publicRows(user, "LUOGU", true, null, null, 100, 0)).extracting(row -> row.get("problemKey")).contains(key, key + "0");
        var total = ranking.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", "LUOGU", null, null, 200);
        assertThat(((java.util.Map<?,?>)total.get("myRank")).get("score")).isEqualTo(4L);
        var week = ranking.ranking(user, "GLOBAL", "WEEK", "FIRST_AC", "LUOGU", null, null, 200);
        assertThat(((java.util.Map<?,?>)week.get("myRank")).get("score")).isEqualTo(2L);
        var from = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(7);
        var to = from.plusDays(7);
        assertThat(daily.heatmap(user, from, to, "LUOGU", false)).anySatisfy(row -> {
            assertThat(row).containsEntry("metric", "FIRST_AC");
            assertThat(((Number) row.get("value")).longValue()).isEqualTo(2L);
        });
        Long second = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LUOGU',?,'VERIFIED') returning id", Long.class, user, "second-" + UUID.randomUUID());
        Long shared = jdbc.queryForObject("select id from problem where platform='LUOGU' and problem_key=?", Long.class, key);
        solved.upsert(user, second, shared, "LUOGU", null, "UNKNOWN", "SYNC");
        try {
            assertThat(statistics.profile(user).get("totalPassed")).isEqualTo(4L);
            assertThat(statistics.profileForOthers(user, true).get("totalPassed")).isEqualTo(4L);
            var duplicateWeek = ranking.ranking(user, "GLOBAL", "WEEK", "FIRST_AC", "LUOGU", null, null, 200);
            assertThat(((java.util.Map<?,?>)duplicateWeek.get("myRank")).get("score")).isEqualTo(2L);
        } finally { jdbc.update("delete from platform_account where id=?", second); }
        Long school = jdbc.queryForObject("select id from school order by id limit 1", Long.class);
        jdbc.update("insert into school_member(school_id,user_id,status) values (?,?,'APPROVED')", school, user);
        assertThat(activity.schoolFeed(school, null, "LUOGU", user, null, null, 100)).extracting(row -> row.get("problemKey")).contains(key, "P1001");
    }

    @Test void clientMetadataCannotOverwriteTheOfficialCatalogOrTrustedRecords() {
        Long problem = jdbc.queryForObject("select id from problem where platform='LUOGU' and problem_key='P1001'", Long.class);
        jdbc.update("update problem set title='official title',difficulty_raw='3',difficulty_label='official difficulty',difficulty_source='OFFICIAL' where id=?", problem);
        Instant trustedAt = Instant.now().minusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        solved.upsert(user, account, problem, "LUOGU", trustedAt, "EXACT", "SYNC");
        submissions.upsert(account, problem, "LUOGU", "930000001", "WA", false, trustedAt, "EXACT", "Java", null);
        accounts.markSuccess(account, true);
        String ticket = service.begin(user, account).get("ticket");
        service.pageWithTicket(ticket, new Page(1, 1, 1, List.of(
                new BrowserRecord("930000001", "730187", "P1001", "forged title", 8,
                        trustedAt.minusSeconds(86400).getEpochSecond(), "C++"))));

        var metadata = jdbc.queryForMap("select title,difficulty_raw,difficulty_label,difficulty_source from problem where id=?", problem);
        assertThat(metadata).containsEntry("title", "official title").containsEntry("difficulty_raw", "3")
                .containsEntry("difficulty_label", "official difficulty").containsEntry("difficulty_source", "OFFICIAL");
        var submission = jdbc.queryForMap("select verdict,accepted,time_precision,submitted_at from submission where account_id=?", account);
        assertThat(submission).containsEntry("verdict", "WA").containsEntry("accepted", false).containsEntry("time_precision", "EXACT");
        assertThat(((java.sql.Timestamp) submission.get("submitted_at")).toInstant()).isEqualTo(trustedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject("select first_ac_confidence from user_solved_problem where account_id=? and problem_id=?", String.class, account, problem)).isEqualTo("EXACT");
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?", Boolean.class, account)).isTrue();
    }

    @Test void trustedSyncReplacesReportedTimesAndPublicSnapshotsDoNotPromoteThem() {
        Long problem = jdbc.queryForObject("select id from problem where platform='LUOGU' and problem_key='P1001'", Long.class);
        Instant reportedAt = Instant.parse("2020-01-01T00:00:00Z");
        String ticket = service.begin(user, account).get("ticket");
        service.pageWithTicket(ticket, new Page(1, 1, 1, List.of(
                new BrowserRecord("940000001", "730187", "P1001", "client title", 8,
                        reportedAt.getEpochSecond(), "C++"))));
        solved.upsert(user, account, problem, "LUOGU", null, "UNKNOWN", "SYNC");
        solved.promoteConfidence(account);
        assertThat(jdbc.queryForObject("select first_ac_confidence from user_solved_problem where account_id=? and problem_id=?", String.class, account, problem)).isEqualTo("USER_REPORTED");

        Instant trustedAt = Instant.parse("2026-09-20T00:00:00Z");
        submissions.upsert(account, problem, "LUOGU", "940000001", "AC", true, trustedAt, "EXACT", "Java", null);
        solved.upsert(user, account, problem, "LUOGU", trustedAt, "EARLIEST_KNOWN", "SYNC");
        solved.promoteConfidence(account);
        assertThat(jdbc.queryForMap("select time_precision,submitted_at from submission where account_id=?", account))
                .containsEntry("time_precision", "EXACT").containsEntry("submitted_at", java.sql.Timestamp.from(trustedAt));
        assertThat(jdbc.queryForMap("select first_ac_confidence,earliest_known_ac_at from user_solved_problem where account_id=? and problem_id=?", account, problem))
                .containsEntry("first_ac_confidence", "EXACT").containsEntry("earliest_known_ac_at", java.sql.Timestamp.from(trustedAt));
    }

    @Test void staleSnapshotSuccessCannotUndoCompletedHistory() {
        PlatformAccount stale = accounts.findById(account);
        assertThat(stale.isHistoryComplete()).isFalse();
        accounts.markSuccess(account, true);
        accounts.markSnapshotSuccess(account, stale.isHistoryComplete());
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?", Boolean.class, account)).isTrue();
    }

    @Test void waitingPageCannotUseRotatedTicketToWriteIntoTheNewImport() throws Exception {
        String oldTicket = service.begin(user, account).get("ticket");
        RaceResult result = racePageAgainstAccountChange(oldTicket,
                () -> service.begin(user, account).get("ticket"));
        assertThat(result.rejected()).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select next_page from luogu_browser_import where account_id=?", Integer.class, account))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from luogu_browser_import_record where account_id=?", Integer.class, account))
                .isZero();
        assertThat(service.pageWithTicket(result.changeResult(), new Page(1, 0, 1, List.of())).complete()).isTrue();
    }

    @Test void waitingPageRechecksOwnershipAfterAnAppealRevokesIt() throws Exception {
        String ticket = service.begin(user, account).get("ticket");
        RaceResult result = racePageAgainstAccountChange(ticket, () -> {
            jdbc.update("update platform_account set ownership_status='RESOLVED' where id=?", account);
            return "revoked";
        });
        assertThat(result.rejected()).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from luogu_browser_import_record where account_id=?", Integer.class, account))
                .isZero();
    }

    record RaceResult(String changeResult, ApiException rejected) {}

    @Test void browserImportDoesNotWaitForOfficialProblemMetadataUpdates() throws Exception {
        String ticket = service.begin(user, account).get("ticket");
        var transactions = new TransactionTemplate(transactionManager);
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var catalogUpdate = pool.submit(() -> transactions.execute(status -> {
                // 题库元数据更新持有 NO KEY UPDATE 锁，仍允许外键引用和普通读取。
                jdbc.queryForList("select id from problem where platform='LUOGU' and problem_key in ('P1001','P1002') for no key update");
                held.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("题库锁夹具超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var importing = pool.submit(() -> service.pageWithTicket(ticket, new Page(1, 2, 20, List.of(
                    new BrowserRecord("960000001", "730187", "P1002", "client title", 2,
                            Instant.now().minusSeconds(3600).getEpochSecond(), "C++"),
                    new BrowserRecord("960000002", "730187", "P1001", "client title", 0,
                            Instant.now().minusSeconds(7200).getEpochSecond(), "C++")))));
            assertThat(importing.get(5, TimeUnit.SECONDS).complete()).isTrue();
            release.countDown();
            catalogUpdate.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 两个真实事务：旧请求已读到票据，必须在行锁等待结束后重新校验票据与归属。 */
    private RaceResult racePageAgainstAccountChange(String ticket, Supplier<String> change) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String applicationName = "browser-import-race-" + UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactions.execute(status -> {
                jdbc.execute("set local lock_timeout='10s'");
                jdbc.queryForList("select id from platform_account where id=? for update", account);
                jdbc.queryForList("select account_id from luogu_browser_import where account_id=? for update", account);
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发夹具等待超时");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return change.get();
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        jdbc.execute("set local lock_timeout='10s'");
                        jdbc.queryForObject("select set_config('application_name',?,true)", String.class, applicationName);
                        service.pageWithTicket(ticket, new Page(1, 2, 1, List.of(
                                new BrowserRecord("950000001", "730187", "P1001", "A+B", 0,
                                        Instant.now().minusSeconds(86400).getEpochSecond(), "C++"))));
                    });
                    return (ApiException) null;
                } catch (ApiException error) {
                    return error;
                }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from pg_stat_activity where application_name=? and wait_event_type='Lock')
                        """, Boolean.class, applicationName));
                if (waiting) break;
                Thread.sleep(10);
            }
            assertThat(waiting).as("旧请求确实遇到 PostgreSQL 行锁等待").isTrue();
            release.countDown();
            return new RaceResult(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }
}
