package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.adapter.LuoguPracticeParser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.sync.SyncService;

@SpringBootTest
@Transactional
class LuoguPracticeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncService sync;
    @Autowired com.szpu.algohub.sync.SyncTaskMapper tasks;
    @Autowired StatisticsService stats;
    @MockitoBean PlatformHttpClient http;
    @Test void publicPracticeDoesNotRequireBrowserHistoryOrStoredCredential() {
        when(http.get(eq(Platform.LUOGU), endsWith("/practice"), anyMap(), isNull())).thenReturn(DATA);
        when(http.get(eq(Platform.LUOGU), endsWith("/100001"), anyMap(), isNull())).thenReturn("<html>challenge</html>");
        Long user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('luogu-public-only','x','测试','STUDENT','ACTIVE') returning id", Long.class);
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status,supports_history) values (?,'LUOGU','100001','test','VERIFIED','PUBLIC','IDLE',false) returning id", Long.class, user);
        assertThat(syncOnce(account)).isFalse();
        assertThat(jdbc.queryForObject("select solved_total from total_snapshot where account_id=? order by captured_at desc limit 1", Integer.class, account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?", Integer.class, account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?", Integer.class, account)).isZero();
        assertThat(jdbc.queryForObject("select history_complete from platform_account where id=?", Boolean.class, account)).isFalse();
        verify(http, never()).get(eq(Platform.LUOGU), contains("/record/list"), anyMap(), any());
    }
    /** 每次同步前领取任务：写入要核对执行编号。 */
    private boolean syncOnce(Long account) {
        return com.szpu.algohub.sync.SyncTestTasks.syncOnce(jdbc, tasks, sync, account);
    }
    private static final String DATA = """
        {"data":{"user":{"uid":100001,"passedProblemCount":2},"passed":[
        {"pid":"P1001","name":"A+B","difficulty":1,"tags":["数学","模拟"]},
        {"pid":"P1007","name":"独木桥","difficulty":3}],
        "submitted":[{"pid":"P1381","name":"未通过","difficulty":4}]}}
        """;
    @Test void storesSolvedRelationsAndDifficultyWithoutFakeSubmissions() {
        when(http.get(eq(Platform.LUOGU), endsWith("/100001"), anyMap(), isNull())).thenReturn("""
            {"data":{"user":{"uid":100001},"dailyCounts":{"2026-09-12":[3,5],"2026-09-11":[0,9]}}}
            """);
        when(http.get(eq(Platform.LUOGU), endsWith("/practice"), anyMap(), isNull())).thenReturn(DATA);
        Long user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('luogu-flow','x','测试','STUDENT','ACTIVE') returning id",Long.class);
        Long account=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status,supports_history) values (?,'LUOGU','100001','test','RESOLVED','PUBLIC','IDLE',false) returning id",Long.class,user);
        syncOnce(account); syncOnce(account);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?",Integer.class,account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=? and earliest_known_ac_at is not null",Integer.class,account)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,account)).isZero();
        assertThat(stats.profile(user).get("totalSolvedAcrossPlatforms")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from daily_stat where account_id=? and metric='FIRST_AC'",Integer.class,account)).isZero();
        assertThat(jdbc.queryForObject("select sum(value) from daily_stat where account_id=? and metric='PLATFORM_ACTIVITY'",Integer.class,account)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from daily_stat where account_id=? and metric='PLATFORM_ACTIVITY'",Integer.class,account)).isEqualTo(2);
        when(http.get(eq(Platform.LUOGU), endsWith("/100001"), anyMap(), isNull())).thenReturn("<html>challenge</html>");
        syncOnce(account);
        assertThat(jdbc.queryForObject("select sum(value) from daily_stat where account_id=? and metric='PLATFORM_ACTIVITY'",Integer.class,account)).isEqualTo(3);
        when(http.get(eq(Platform.LUOGU), endsWith("/practice"), anyMap(), isNull())).thenReturn("<html>challenge</html>");
        assertThatThrownBy(() -> syncOnce(account)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?",Integer.class,account)).isEqualTo(2);
    }
    @Test void embeddedHtmlAndWrongUidAndChallenge() {
        assertThat(LuoguPracticeParser.parse("<script id='lentille-context' type='application/json'>"+DATA+"</script>","100001").solvedProblems()).hasSize(2);
        assertThatThrownBy(() -> LuoguPracticeParser.parse(DATA,"999")).isInstanceOf(ApiException.class);
        try { LuoguPracticeParser.parse("<html>Welcome - Luogu Spilopelia</html>","100001"); fail("should reject challenge"); }
        catch(ApiException e) { assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED); }
    }
    @Test void activityRejectsMalformedDatesCountsAndIdentity() {
        String prefix="{\"data\":{\"user\":{\"uid\":100001},\"dailyCounts\":";
        for (String values : java.util.List.of("{\"2026-02-30\":[3,5]}", "{\"2026-09-12\":[-1,5]}", "{\"2026-09-12\":[2147483648,5]}", "[]")) {
            assertThatThrownBy(() -> LuoguPracticeParser.activity(prefix+values+"}}", "100001")).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> LuoguPracticeParser.activity(prefix+"{}}}","999")).isInstanceOf(ApiException.class);
    }
    @Test void publicPracticeDoesNotSendStoredCookie() {
        when(http.get(eq(Platform.LUOGU), endsWith("/practice"), anyMap(), isNull())).thenReturn(DATA);
        when(http.get(eq(Platform.LUOGU), endsWith("/100001"), anyMap(), isNull())).thenReturn("<html>challenge</html>");
        var adapter=new com.szpu.algohub.platform.adapter.LuoguAdapter(http);
        var snapshot=adapter.fetchTotals(new com.szpu.algohub.platform.PlatformDtos.SyncContext(1L,"test","100001",null,"old-cookie",null));
        assertThat(snapshot.solvedTotal()).isEqualTo(2);
        verify(http,never()).get(eq(Platform.LUOGU),anyString(),anyMap(),eq("old-cookie"));
    }
}
