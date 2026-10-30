package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.*;
import com.szpu.algohub.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class BrowserImportRetryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LuoguBrowserImportService luogu;
    @Autowired QojBrowserImportService qoj;
    @Autowired LeetCodeBrowserImportService leetcode;
    Long user, account;
    final long at = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
    record Reply(int received, int total, boolean complete) {}

    @BeforeEach void setup() {
        user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x','example','STUDENT') returning id",
                Long.class,"retry-" + UUID.randomUUID());
    }
    @AfterEach void cleanup() {
        jdbc.update("delete from platform_account where user_id=?", user);
        jdbc.update("delete from app_user where id=?", user);
    }
    String start(String platform) {
        return start(platform, false);
    }
    String start(String platform, boolean auto) {
        if (account == null) account = jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status) values (?,?,'123456','123456','VERIFIED') returning id",
                Long.class,user,platform);
        return switch(platform) {
            case "LUOGU" -> luogu.begin(user,account,auto).get("ticket");
            case "QOJ" -> qoj.begin(user,account,auto).get("ticket");
            default -> leetcode.begin(user,account,auto).get("ticket");
        };
    }
    String autoBegin(String platform, String ticket) {
        return switch (platform) {
            case "LUOGU" -> luogu.autoBegin(ticket).get("ticket");
            case "QOJ" -> qoj.autoBegin(ticket).get("ticket");
            default -> leetcode.autoBegin(ticket).get("ticket");
        };
    }
    void revoke(String platform) {
        switch (platform) {
            case "LUOGU" -> luogu.revokeAuto(user,account);
            case "QOJ" -> qoj.revokeAuto(user,account);
            default -> leetcode.revokeAuto(user,account);
        }
    }

    @ParameterizedTest @ValueSource(strings={"LUOGU","QOJ","LEETCODE_CN"})
    void revocationCancelsInitialAndDerivedTicketsButKeepsCompletedHistory(String platform) {
        String original = start(platform,true);
        revoke(platform);
        assertThatThrownBy(() -> page(platform,original,1,1,at)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> autoBegin(platform,original)).isInstanceOf(ApiException.class);

        String grant = start(platform,true);
        assertThat(page(platform,grant,1,1,at).complete()).isTrue();
        String derived = autoBegin(platform,grant);
        assertThat(page(platform,derived,1,2,at).complete()).isFalse();
        String table = switch(platform) { case "LUOGU" -> "luogu"; case "QOJ" -> "qoj"; default -> "leetcode"; };
        assertThat(jdbc.queryForObject("select count(*) from "+table+"_browser_import_record where account_id=?",Integer.class,account)).isEqualTo(1);
        revoke(platform);
        revoke(platform);
        assertThatThrownBy(() -> page(platform,derived,2,2,at+86400)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> autoBegin(platform,grant)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from "+table+"_browser_import_record where account_id=?",Integer.class,account)).isZero();
        assertThat(submissions()).isEqualTo(1);
        assertThat(page(platform,start(platform),1,1,at).complete()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"LUOGU","QOJ","LEETCODE_CN"})
    void disabledUsersCannotUploadOrStartWithPreviouslyIssuedBrowserTokens(String platform) {
        String ticket = start(platform, true);
        jdbc.update("update app_user set status='DISABLED' where id=?", user);
        assertThatThrownBy(() -> page(platform,ticket,1,1,at))
                .isInstanceOf(ApiException.class).hasMessageContaining("停用");
        assertThatThrownBy(() -> autoBegin(platform,ticket))
                .isInstanceOf(ApiException.class).hasMessageContaining("停用");
        assertThatThrownBy(() -> start(platform))
                .isInstanceOf(ApiException.class).hasMessageContaining("停用");
        assertThat(submissions()).isZero();
        jdbc.update("update app_user set status='ACTIVE' where id=?", user);
        assertThat(page(platform,ticket,1,1,at).complete()).isTrue();
    }
    Reply page(String platform, String ticket, int number, int total, long time) {
        String id = String.valueOf(980000000 + number);
        if (platform.equals("LUOGU")) {
            var rows = total == 0 ? List.<LuoguBrowserImportService.BrowserRecord>of() : List.of(new LuoguBrowserImportService.BrowserRecord(id,"123456","P1001","Example",null,time,"C++"));
            var result=luogu.pageWithTicket(ticket,new LuoguBrowserImportService.Page(number,total,1,rows));
            return new Reply(result.received(),result.total(),result.complete());
        }
        if (platform.equals("QOJ")) {
            var rows = total == 0 ? List.<QojBrowserImportService.BrowserRecord>of() : List.of(new QojBrowserImportService.BrowserRecord(id,"123456","1001","Example",null,time,"C++"));
            var result=qoj.pageWithTicket(ticket,new QojBrowserImportService.Page(number,total,1,rows));
            return new Reply(result.received(),result.total(),result.complete());
        }
        var rows = total == 0 ? List.<LeetCodeBrowserImportService.BrowserRecord>of() : List.of(new LeetCodeBrowserImportService.BrowserRecord(id,"123456","example-retry","Example",null,time,"C++"));
        var result=leetcode.pageWithTicket(ticket,new LeetCodeBrowserImportService.Page(number,total,1,rows));
        return new Reply(result.received(),result.total(),result.complete());
    }
    int submissions() { return jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,account); }

    @ParameterizedTest @ValueSource(strings={"LUOGU","QOJ","LEETCODE_CN"})
    void lostAcknowledgementsReplayWithoutDuplicateImportOrChangingFirstAc(String platform) {
        String ticket=start(platform);
        var first=page(platform,ticket,1,2,at);
        assertThat(page(platform,ticket,1,2,at)).isEqualTo(first);
        assertThat(submissions()).isZero();
        assertThatThrownBy(()->page(platform,ticket,1,2,at+1)).isInstanceOf(ApiException.class);
        var last=page(platform,ticket,2,2,at+86400);
        var stats=jdbc.queryForList("select metric,stat_date,value from daily_stat where account_id=? order by metric,stat_date",account);
        var solved=jdbc.queryForList("select problem_id,earliest_known_ac_at,first_ac_confidence from user_solved_problem where account_id=?",account);
        assertThat(last.complete()).isTrue();
        assertThat(page(platform,ticket,2,2,at+86400)).isEqualTo(last);
        assertThat(page(platform,ticket,1,2,at)).isEqualTo(first);
        assertThat(submissions()).isEqualTo(2);
        assertThat(jdbc.queryForList("select metric,stat_date,value from daily_stat where account_id=? order by metric,stat_date",account)).isEqualTo(stats);
        assertThat(jdbc.queryForList("select problem_id,earliest_known_ac_at,first_ac_confidence from user_solved_problem where account_id=?",account)).isEqualTo(solved);
        assertThatThrownBy(()->page(platform,ticket,2,2,at+1)).isInstanceOf(ApiException.class);
        String rotated=start(platform);
        assertThatThrownBy(()->page(platform,ticket,2,2,at+86400)).isInstanceOf(ApiException.class);
        assertThat(page(platform,rotated,1,1,at-1).complete()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"LUOGU","QOJ","LEETCODE_CN"})
    void concurrentLastPageRetriesCommitOnce(String platform) throws Exception {
        String ticket=start(platform);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return page(platform,ticket,1,1,at);});
            var b=pool.submit(()->{start.await();return page(platform,ticket,1,1,at);});
            start.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
            assertThat(submissions()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"LUOGU","QOJ","LEETCODE_CN"})
    void emptyCompletionCanReplayButExpiredReceiptCannot(String platform) {
        String ticket=start(platform);
        assertThat(page(platform,ticket,1,0,at).complete()).isTrue();
        assertThat(page(platform,ticket,1,0,at).complete()).isTrue();
        String table = switch(platform) { case "LUOGU" -> "luogu"; case "QOJ" -> "qoj"; default -> "leetcode"; };
        assertThat(jdbc.queryForObject("select count(*) from "+table+"_browser_import_record where account_id=?",Integer.class,account)).isZero();
        jdbc.update("update "+table+"_browser_import set expires_at=now()-interval '1 second' where account_id=?",account);
        assertThatThrownBy(()->page(platform,ticket,1,0,at)).isInstanceOf(ApiException.class);
    }
}
