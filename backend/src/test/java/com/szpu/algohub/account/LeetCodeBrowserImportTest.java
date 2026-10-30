package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.szpu.algohub.account.LeetCodeBrowserImportService.*;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.statistics.*;
import com.szpu.algohub.activity.ActivityMapper;

@SpringBootTest
class LeetCodeBrowserImportTest {
    @Autowired LeetCodeBrowserImportService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired SolvedMapper solved;
    @Autowired SubmissionMapper submissions;
    @Autowired DailyStatMapper daily;
    @Autowired ActivityMapper activity;
    private Long user, account;
    private final Instant early = Instant.parse("2025-01-01T16:00:00Z");
    private final Instant later = Instant.parse("2025-02-01T16:00:00Z");

    @BeforeEach void setup() {
        user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x','example','STUDENT') returning id",Long.class,"lc-"+UUID.randomUUID());
        account=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status) values (?,'LEETCODE_CN',?,'example-user','VERIFIED') returning id",Long.class,user,"example-"+user);
    }
    @AfterEach void cleanup() {
        jdbc.update("delete from platform_account where id=?",account);
        jdbc.update("delete from app_user where id=?",user);
    }
    BrowserRecord row(String id,String pid,Instant at) {
        return new BrowserRecord(id,"example-user",pid,"Example",null,at.getEpochSecond(),"C++");
    }
    void upload(List<BrowserRecord> rows) {
        service.pageWithTicket(service.begin(user,account).get("ticket"),new Page(1,rows.size(),100,rows));
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where account_id=?",Integer.class,account); }
    Long problem(String key) { return jdbc.queryForObject("select id from problem where platform='LEETCODE_CN' and problem_key=?",Long.class,key); }

    @Test void moreThanFifteenAndRepeatedAcRemainRecordsButHeatmapCountsOnlyFirstAc() {
        var rows=new ArrayList<BrowserRecord>();
        for(int i=0;i<20;i++) rows.add(row(""+(100000+i),"example-"+i,early));
        rows.add(row("100100","example-0",later));
        String ticket=service.begin(user,account).get("ticket");
        assertThat(service.pageWithTicket(ticket,new Page(1,21,20,rows.subList(0,20))).complete()).isFalse();
        assertThat(count("submission")).isZero();
        assertThat(service.pageWithTicket(ticket,new Page(2,21,20,rows.subList(20,21))).complete()).isTrue();
        assertThat(count("submission")).isEqualTo(21);
        assertThat(count("user_solved_problem")).isEqualTo(20);
        assertThat(jdbc.queryForObject("select sum(value) from daily_stat where account_id=? and metric='FIRST_AC'",Long.class,account)).isEqualTo(20);
        var heatmap=daily.heatmap(user,LocalDate.parse("2025-01-01"),LocalDate.parse("2025-03-01"),"LEETCODE_CN",false);
        assertThat(heatmap.stream().filter(r->"FIRST_AC".equals(r.get("metric"))).toList()).singleElement().satisfies(r->{
            assertThat(r.get("statDate").toString()).isEqualTo("2025-01-02");
            assertThat(((Number)r.get("value")).intValue()).isEqualTo(20);
        });
        upload(rows);
        assertThat(count("submission")).isEqualTo(21);
        assertThat(count("user_solved_problem")).isEqualTo(20);
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?",Boolean.class,account)).isFalse();
        assertThat(activity.schoolFeed(null,null,"LEETCODE_CN",user,null,null,100)).hasSize(20);
        jdbc.update("update platform_account set ownership_status='UNVERIFIED' where id=?",account);
        assertThat(activity.schoolFeed(null,null,"LEETCODE_CN",user,null,null,100)).isEmpty();
    }

    @Test void earlierBrowserAcCorrectsRecentPublicAcAndSurvivesNormalSync() {
        upload(List.of(row("200001","mixedCase-LCR",later)));
        Long pid=problem("mixedCase-LCR");
        solved.upsert(user,account,pid,"LEETCODE_CN",later,"EARLIEST_KNOWN","SYNC");
        upload(List.of(row("200001","mixedCase-LCR",later),row("200002","mixedCase-LCR",early)));
        solved.upsert(user,account,pid,"LEETCODE_CN",later.plusSeconds(86400),"EARLIEST_KNOWN","SYNC");
        assertThat(jdbc.queryForMap("select earliest_known_ac_at,first_ac_confidence from user_solved_problem where account_id=?",account))
            .containsEntry("earliest_known_ac_at",java.sql.Timestamp.from(early)).containsEntry("first_ac_confidence","USER_REPORTED");
        // Independently found earlier evidence invalidates a claimed first-AC date until history is read again.
        solved.upsert(user,account,pid,"LEETCODE_CN",early.minusSeconds(86400),"EARLIEST_KNOWN","SYNC");
        assertThat(jdbc.queryForObject("select first_ac_confidence from user_solved_problem where account_id=?",String.class,account)).isEqualTo("EARLIEST_KNOWN");
    }

    @Test void rejectsWrongOwnerRevokedExpiredOrReplayedTicketAndPartialPages() {
        assertThatThrownBy(()->service.begin(-1L,account)).isInstanceOf(ApiException.class);
        String ticket=service.begin(user,account).get("ticket");
        var first=row("300001","example-a",early);
        assertThatThrownBy(()->service.pageWithTicket(ticket,new Page(2,2,1,List.of(first)))).isInstanceOf(ApiException.class);
        service.pageWithTicket(ticket,new Page(1,2,1,List.of(first)));
        assertThatThrownBy(()->service.pageWithTicket(ticket,new Page(2,2,1,List.of(first)))).isInstanceOf(ApiException.class);
        assertThat(count("submission")).isZero();
        String rotated=service.begin(user,account).get("ticket");
        assertThatThrownBy(()->service.pageWithTicket(ticket,new Page(1,1,1,List.of(first)))).isInstanceOf(ApiException.class);
        jdbc.update("update leetcode_browser_import set expires_at=now()-interval '1 second' where account_id=?",account);
        assertThatThrownBy(()->service.pageWithTicket(rotated,new Page(1,1,1,List.of(first)))).isInstanceOf(ApiException.class);
        jdbc.update("update platform_account set ownership_status='RESOLVED' where id=?",account);
        assertThatThrownBy(()->service.begin(user,account)).isInstanceOf(ApiException.class);
    }

    @Test void autoGrantIsScopedAndRevocable() {
        String ticket=service.begin(user,account,true).get("ticket");
        assertThat(service.pageWithTicket(ticket,new Page(1,0,100,List.of())).autoEnabled()).isTrue();
        assertThat(service.autoBegin(ticket).get("uid")).isEqualTo("example-user");
        service.revokeAuto(user,account);
        assertThatThrownBy(()->service.autoBegin(ticket)).isInstanceOf(ApiException.class);
    }

    @Test void browserDoesNotOverwriteTrustedRecordOrOfficialTotal() {
        upload(List.of(row("400001","example-protected",early)));
        Long pid=problem("example-protected");
        submissions.upsert(account,pid,"LEETCODE_CN","400001","AC",true,later,"EXACT","Java",null);
        solved.upsert(user,account,pid,"LEETCODE_CN",later,"EXACT","SYNC");
        jdbc.update("insert into total_snapshot(account_id,solved_total,captured_at) values (?,42,now())",account);
        upload(List.of(row("400001","example-protected",early)));
        assertThat(jdbc.queryForMap("select submitted_at,time_precision,language from submission where account_id=?",account))
            .containsEntry("submitted_at",java.sql.Timestamp.from(later)).containsEntry("time_precision","EXACT").containsEntry("language","Java");
        assertThat(jdbc.queryForObject("select solved_total from total_snapshot where account_id=?",Integer.class,account)).isEqualTo(42);
        assertThat(jdbc.queryForObject("select first_ac_confidence from user_solved_problem where account_id=?",String.class,account)).isEqualTo("EXACT");
    }
}
