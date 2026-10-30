package com.szpu.algohub.statistics;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.LoginUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.TimeSupport;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CampusRankingTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CampusRankingService service;
    @Autowired MonthlyReportMapper reports;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @Autowired MockMvc mvc;
    Long school;
    Long me;

    @Autowired StatisticsService statistics;
    @Autowired com.szpu.algohub.activity.ActivityMapper activity;

    @Test void unverifiedLuoguDetailsCannotInflateVerifiedCumulativeTotals() {
        Long verified = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LUOGU','ranking-verified-luogu','VERIFIED') returning id", Long.class, me);
        Long unverified = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LUOGU','ranking-unverified-luogu','UNVERIFIED') returning id", Long.class, me);
        Long first = jdbc.queryForObject("insert into problem(platform,problem_key) values ('LUOGU','ranking-verified-problem') returning id", Long.class);
        Long second = jdbc.queryForObject("insert into problem(platform,problem_key) values ('LUOGU','ranking-unverified-problem') returning id", Long.class);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'LUOGU'),(?,?,?,'LUOGU'),(?,?,?,'LUOGU')", me, verified, first, me, unverified, first, me, unverified, second);
        long previous = 45L; // Existing verified Codeforces details from setup.
        var profile = statistics.profile(me);
        assertThat(profile.get("totalPassed")).isEqualTo(previous + 1);
        assertThat(profile.get("totalSolvedAcrossPlatforms")).isEqualTo(previous + 2);
        for (String scope : new String[]{"SCHOOL", "GLOBAL"}) {
            assertThat(((Map<?, ?>) service.ranking(me, scope, "TOTAL", "FIRST_AC", null, null, null, 200).get("myRank")).get("score"))
                    .isEqualTo(profile.get("totalPassed"));
        }
        assertThat(statistics.profileForOthers(me, true).get("totalSolvedAcrossPlatforms")).isEqualTo(previous + 1);
    }

    @Test void cumulativeExampleKeeps672TotalSeparateFrom295UntimedDetails() {
        Long leetcode = jdbc.queryForObject("select id from platform_account where user_id=?", Long.class, me);
        jdbc.update("delete from user_solved_problem where account_id=?", leetcode);
        jdbc.update("delete from daily_stat where account_id=?", leetcode);
        jdbc.update("update platform_account set platform='LEETCODE_CN',supports_history=false,history_complete=false,last_success_at=null where id=?", leetcode);
        Long luogu = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LUOGU','ranking-example-luogu','VERIFIED') returning id", Long.class, me);
        Long otherLuogu = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LUOGU','ranking-example-luogu-other','VERIFIED') returning id", Long.class, me);
        Long nowcoder = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'NOWCODER','ranking-example-nowcoder','VERIFIED') returning id", Long.class, me);
        // now() is stable within this transaction: the later ID must win the tie,
        // even though its cumulative value is smaller than the earlier snapshot.
        jdbc.update("insert into total_snapshot(account_id,solved_total,captured_at) values (?,999,now()-interval '1 day'),(?,500,now()),(?,389,now()),(?,250,now()),(?,200,now()),(?,33,now())",
                leetcode, leetcode, leetcode, luogu, otherLuogu, nowcoder);
        for (var entry : Map.of("LEETCODE_CN", 45, "LUOGU", 250).entrySet()) {
            String platform = entry.getKey();
            Long account = "LUOGU".equals(platform) ? luogu : leetcode;
            jdbc.update("insert into problem(platform,problem_key) select ?, 'ranking-example-' || n from generate_series(1,?) n", platform, entry.getValue());
            jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) select ?,?,id,platform from problem where platform=? and problem_key like 'ranking-example-%'", me, account, platform);
        }
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) select ?,?,problem_id,'LUOGU' from user_solved_problem where account_id=?", me, otherLuogu, luogu);
        jdbc.update("insert into luogu_extra_solve(account_id,problem_key) values (?,'ranking-example-private')", otherLuogu);
        var profile = statistics.profile(me);
        assertThat(profile.get("totalPassed")).isEqualTo(672L);
        assertThat(profile.get("totalSolvedAcrossPlatforms")).isEqualTo(295L);
        assertThat(statistics.snapshots(me)).filteredOn(row -> "LEETCODE_CN".equals(row.get("platform")))
                .singleElement().satisfies(row -> assertThat(((Number)row.get("solvedTotal")).longValue()).isEqualTo(389L));
        for (String scope : new String[]{"SCHOOL", "GLOBAL"}) {
            var mine = (Map<?, ?>) service.ranking(me, scope, "TOTAL", "FIRST_AC", null, null, null, 200).get("myRank");
            assertThat(mine.get("score")).isEqualTo(profile.get("totalPassed"));
            assertThat(mine.get("snapshotOnlySolved")).isEqualTo(377L);
            assertThat(((Map<?, ?>) service.ranking(me, scope, "TOTAL", "FIRST_AC", "LUOGU", null, null, 200).get("myRank")).get("score")).isEqualTo(250L);
            assertThat(service.ranking(me, scope, "WEEK", "FIRST_AC", null, null, null, 200).get("myRank")).isNull();
        }
        assertThat((Map<?, ?>)statistics.heatmap(me, TimeSupport.today().minusDays(365), TimeSupport.today(), null).get("metrics")).isEmpty();
        assertThat(activity.schoolFeed(school, null, null, me, null, null, 100)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id in (select id from platform_account where user_id=?)", Long.class, me)).isZero();
    }

    @Test void cumulativeUsesLatestSnapshotsWithoutInventingDailyHistory() {
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'LEETCODE_CN','example','VERIFIED') returning id", Long.class, me);
        jdbc.update("insert into total_snapshot(account_id,solved_total,captured_at) values (?,999,now()-interval '1 day'),(?,389,now())",account,account);
        for (String scope : new String[]{"SCHOOL","GLOBAL"}) {
            var board=service.ranking(me,scope,"TOTAL","FIRST_AC",null,null,null,200);
            assertThat(((Map<?,?>)board.get("myRank")).get("score")).isEqualTo(statistics.profile(me).get("totalPassed"));
            var filtered=service.ranking(me,scope,"TOTAL","FIRST_AC","LEETCODE_CN",null,null,200);
            assertThat(((Map<?,?>)filtered.get("myRank")).get("score")).isEqualTo(389L);
            assertThat(service.ranking(me,scope,"WEEK","FIRST_AC","LEETCODE_CN",null,null,200).get("myRank")).isNull();
        }
        jdbc.update("update platform_account set ownership_status='RESOLVED' where id=?",account);
        sqlSession.clearCache();
        assertThat(service.ranking(me,"GLOBAL","TOTAL","FIRST_AC","LEETCODE_CN",null,null,200).get("myRank")).isNull();
    }

    @BeforeEach void setup() {
        school = jdbc.queryForObject("insert into school(name,invite_code) values ('排名测试学校','ranking-test') returning id", Long.class);
        for (int i = 0; i < 60; i++) {
            Long id = member("rank-" + i, school, "一班", i < 2 ? 100 : 100 - i, "VERIFIED", true);
            if (i == 55) me = id;
        }
    }

    Long member(String name, Long schoolId, String clazz, int score, String ownership, boolean history) {
        Long user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name) values (?,'x',?) returning id", Long.class, name, name);
        jdbc.update("insert into school_member(school_id,user_id,status,enrollment_year,class_name) values (?,?,'APPROVED',2026,?)", schoolId, user, clazz);
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status,supports_history,history_complete,last_success_at) values (?,'CODEFORCES',?,?,?, ?,now()) returning id", Long.class, user, name, ownership, history, history);
        if (history) {
            jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'FIRST_AC',?,'SUBMISSION')", user, account, TimeSupport.today(), score);
            jdbc.update("insert into problem(platform,problem_key) select 'CODEFORCES','rank-fixture-' || n from generate_series(1,?) n on conflict do nothing",score);
            jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence) select ?,?,p.id,'CODEFORCES',?,'EXACT' from problem p join generate_series(1,?) n on p.platform='CODEFORCES' and p.problem_key='rank-fixture-' || n",user,account,java.sql.Timestamp.from(TimeSupport.campusDayStart(TimeSupport.today())),score);
        }
        return user;
    }

    Long withoutSchool(String name, String ownership) {
        Long user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name) values (?,'x',?) returning id", Long.class, name, name);
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status,supports_history,history_complete,last_success_at) values (?,'CODEFORCES',?,?,true,true,now()) returning id", Long.class, user, name, ownership);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence) select ?,?,p.id,'CODEFORCES',?,'EXACT' from problem p where p.platform='CODEFORCES' and p.problem_key='rank-fixture-1'", user, account, java.sql.Timestamp.from(TimeSupport.campusDayStart(TimeSupport.today())));
        return user;
    }

    @SuppressWarnings("unchecked")
    @Test void globalRankingIncludesVerifiedUsersWithoutInviteCodeAndKeepsSchoolScope() {
        long globalBefore = ((Number) service.ranking(me, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200)
                .get("participantCount")).longValue();
        Long independent = withoutSchool("rank-independent", "VERIFIED");
        Long pending = withoutSchool("rank-pending", "VERIFIED");
        jdbc.update("insert into school_member(school_id,user_id,status) values (?,?,'PENDING')", school, pending);
        Long hidden = withoutSchool("rank-global-hidden", "VERIFIED");
        jdbc.update("insert into user_privacy(user_id,show_on_ranking) values (?,false)", hidden);
        Long privateUser = withoutSchool("rank-global-private", "VERIFIED");
        jdbc.update("insert into user_privacy(user_id,profile_visibility) values (?,'PRIVATE')", privateUser);
        withoutSchool("rank-global-unverified", "UNVERIFIED");

        var global = service.ranking(independent, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200);
        var rows = (java.util.List<Map<String,Object>>) global.get("rows");
        assertThat(global.get("participantCount")).isEqualTo(globalBefore + 2);
        assertThat(rows).extracting(row -> row.get("userId")).contains(independent, pending)
                .doesNotContain(hidden, privateUser);
        assertThat(((Map<String,Object>) global.get("myRank")).get("schoolName")).isNull();
        assertThat(service.ranking(independent, "SCHOOL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(0L);
        assertThat(service.ranking(me, "SCHOOL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(60L);
        assertThat(service.ranking(me, "GLOBAL", "TOTAL", "FIRST_AC", null, 2026, null, 200).get("myRank")).isNotNull();
        jdbc.update("update app_user set enrollment_year=2025 where id=?", independent);
        var filtered = service.ranking(independent, "GLOBAL", "TOTAL", "FIRST_AC", null, 2025, null, 200);
        assertThat(filtered.get("myRank")).isNotNull();
        assertThat((java.util.List<Map<String,Object>>) filtered.get("rows")).allSatisfy(row -> assertThat(row.get("enrollmentYear")).isEqualTo(2025));
        assertThat(service.ranking(me, "GLOBAL", "TOTAL", "FIRST_AC", null, 2025, null, 200).get("myRankReason")).isEqualTo("YEAR");
    }

    @Test void luoguPublicListJoinsCumulativeWithoutBrowserHistoryButNotTimedBoards() {
        long globalBefore = ((Number) service.ranking(me, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200)
                .get("participantCount")).longValue();
        Long user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name) values ('rank-luogu','x','rank-luogu') returning id", Long.class);
        jdbc.update("insert into school_member(school_id,user_id,status) values (?,?,'APPROVED')", school, user);
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status,last_success_at) values (?,'LUOGU','rank-luogu','VERIFIED',now()) returning id", Long.class, user);
        Long problem = jdbc.queryForObject("insert into problem(platform,problem_key) values ('LUOGU','rank-luogu-p1') returning id", Long.class);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'LUOGU')", user, account, problem);

        jdbc.update("insert into luogu_extra_solve(account_id,problem_key) values (?,'private-extra')", account);
        assertThat(service.ranking(user, "SCHOOL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(61L);
        assertThat(service.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(globalBefore + 1);
        for (String scope : java.util.List.of("SCHOOL", "GLOBAL")) {
            var total = service.ranking(user, scope, "TOTAL", "FIRST_AC", "LUOGU", null, null, 200);
            assertThat(((Map<?, ?>) total.get("myRank")).get("solved")).isEqualTo(1L);
            assertThat(((Map<?, ?>) total.get("myRank")).get("uniqueSolved")).isEqualTo(1L);
            assertThat((java.util.List<?>) total.get("coverage")).hasSize(1);
            for (String period : java.util.List.of("WEEK", "MONTH", "YEAR")) {
                assertThat(service.ranking(user, scope, period, "FIRST_AC", "LUOGU", null, null, 200).get("participantCount")).isEqualTo(0L);
            }
        }

        jdbc.update("update platform_account set history_complete=true where id=?", account);
        jdbc.update("update user_solved_problem set earliest_known_ac_at=now(), first_ac_confidence='EXACT' where account_id=?", account);
        sqlSession.clearCache();
        assertThat(service.ranking(user, "SCHOOL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(61L);
        assertThat(service.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200).get("participantCount")).isEqualTo(globalBefore + 1);
        assertThat(((Map<?, ?>) service.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", null, null, null, 200).get("myRank")).get("solved")).isEqualTo(1L);
        Long second = jdbc.queryForObject("insert into problem(platform,problem_key) values ('LUOGU','rank-luogu-p2') returning id", Long.class);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'LUOGU')",user,account,second);
        sqlSession.clearCache();
        assertThat(((Map<?, ?>) service.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", "LUOGU", null, null, 200).get("myRank")).get("score")).isEqualTo(2L);
        assertThat(((Map<?, ?>) service.ranking(user, "GLOBAL", "WEEK", "FIRST_AC", "LUOGU", null, null, 200).get("myRank")).get("score")).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    @Test void verifiedMatijiSnapshotJoinsCumulativeButNotTimedRanking() {
        Long account = jdbc.queryForObject("""
            insert into platform_account(user_id,platform,handle,ownership_status,last_success_at)
            values (?,'MATIJI','rank-matiji','VERIFIED',now()) returning id
            """, Long.class, me);
        jdbc.update("insert into total_snapshot(account_id,solved_total,captured_at) values (?,100,now()-interval '1 day')", account);
        jdbc.update("insert into total_snapshot(account_id,solved_total,captured_at) values (?,168,now())", account);

        var all = service.ranking(me, "SCHOOL", "TOTAL", "FIRST_AC", null, null, null, 200);
        var mine = (Map<String,Object>) all.get("myRank");
        assertThat(mine.get("score")).isEqualTo(213L);
        assertThat(mine.get("snapshotOnlySolved")).isEqualTo(168L);
        assertThat(((java.util.List<Map<String,Object>>) all.get("coverage")))
                .anySatisfy(row -> assertThat(row.get("platform")).isEqualTo("MATIJI"));

        var matiji = service.ranking(me, "SCHOOL", "TOTAL", "FIRST_AC", "MATIJI", null, null, 200);
        assertThat(matiji.get("participantCount")).isEqualTo(1L);
        assertThat(((Map<?, ?>) matiji.get("myRank")).get("score")).isEqualTo(168L);
        assertThat(service.ranking(me, "SCHOOL", "WEEK", "FIRST_AC", "MATIJI", null, null, 200)
                .get("participantCount")).isEqualTo(0L);
    }

    @SuppressWarnings("unchecked")
    @Test void ranksBeforeLimitAndReturnsNeighborsWithTies() {
        Map<String,Object> result = service.ranking(me, "WEEK", "FIRST_AC", null, 2026, null, 50);
        var rows = (java.util.List<Map<String,Object>>) result.get("rows");
        var neighbors = (java.util.List<Map<String,Object>>) result.get("neighbors");
        var mine = (Map<String,Object>) result.get("myRank");
        assertThat(rows).hasSize(50);
        assertThat(rows.get(0).get("rank")).isEqualTo(1L);
        assertThat(rows.get(1).get("rank")).isEqualTo(1L);
        assertThat(rows.get(2).get("rank")).isEqualTo(3L);
        assertThat(mine.get("rank")).isEqualTo(56L);
        assertThat(neighbors).hasSize(5);
        assertThat(result.get("participantCount")).isEqualTo(60L);
    }

    @SuppressWarnings("unchecked")
    @Test void scopesSchoolClassPrivacyOwnershipAndMetricCapability() {
        Long otherSchool = jdbc.queryForObject("insert into school(name,invite_code) values ('另一个学校','ranking-other') returning id",Long.class);
        member("rank-outside", otherSchool, "一班", 500, "VERIFIED", true);
        member("rank-other-class", school, "二班", 500, "VERIFIED", true);
        Long hidden = member("rank-hidden", school, "一班", 500, "VERIFIED", true);
        jdbc.update("insert into user_privacy(user_id,show_on_ranking) values (?,false)", hidden);
        member("rank-mismatch", school, "一班", 500, "MISMATCH", true);
        member("rank-snapshot", school, "一班", 500, "VERIFIED", false);
        var result = service.ranking(me, "WEEK", "FIRST_AC", null, 2026, "一班", 50);
        assertThat(result.get("participantCount")).isEqualTo(60L);
        var coverage = (java.util.List<Map<String,Object>>) result.get("coverage");
        assertThat(coverage).hasSize(1);
        assertThat(coverage.getFirst().get("users")).isEqualTo(60L);
        assertThat(service.ranking(hidden, "WEEK", "FIRST_AC", null, 2026, "一班", 50).get("myRank")).isNull();
        assertThat(service.ranking(me, "WEEK", "FIRST_AC", null, 2025, null, 50).get("participantCount")).isEqualTo(0L);
    }

    @Test void ignoresSnapshotsAndOutOfRangeDatesAndRejectsInvalidFilters() {
        Long account = jdbc.queryForObject("select id from platform_account where user_id=?", Long.class, me);
        jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'OBSERVED_DELTA',900,'SNAPSHOT_DELTA')", me, account, TimeSupport.today());
        jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'FIRST_AC',900,'SUBMISSION')", me, account, TimeSupport.today().minusDays(7));
        var result = service.ranking(me, "WEEK", "FIRST_AC", null, 2026, null, 50);
        assertThat(((Map<?,?>) result.get("myRank")).get("score")).isEqualTo(45L);
        assertThatThrownBy(() -> service.ranking(me,"INVALID","FIRST_AC",null,null,null,50)).hasMessageContaining("参数");
        assertThatThrownBy(() -> service.ranking(me,"WEEK","OBSERVED_DELTA",null,null,null,50)).hasMessageContaining("参数");
    }

    @Test void yearRangeCoversTheLast365DaysIncludingToday() {
        // 准备数据里「我」今天通过 45 题。先改数据再查：同一事务里 MyBatis 会缓存相同参数的查询，不能先查一遍做对照
        var mine = jdbc.queryForList("select id from user_solved_problem where user_id=? order by id limit 2", Long.class, me);
        // 挪到 200 天前的：出了近 30 天，还在近一年；挪到 365 天前的（含今天是第 366 天）：两个都不算
        jdbc.update("update user_solved_problem set earliest_known_ac_at=? where id=?",
                java.sql.Timestamp.from(TimeSupport.campusDayStart(TimeSupport.today().minusDays(200))), mine.get(0));
        jdbc.update("update user_solved_problem set earliest_known_ac_at=? where id=?",
                java.sql.Timestamp.from(TimeSupport.campusDayStart(TimeSupport.today().minusDays(365))), mine.get(1));
        var result = service.ranking(me, "YEAR", "FIRST_AC", null, 2026, null, 50);
        assertThat(yearScore("MONTH")).isEqualTo(43L);
        assertThat(((Map<?, ?>) result.get("myRank")).get("score")).isEqualTo(44L);
        assertThat(result.get("from")).isEqualTo(TimeSupport.today().minusDays(364));
        assertThat(result.get("to")).isEqualTo(TimeSupport.today());
        assertThat((String) result.get("description")).startsWith("近一年");
    }

    private long yearScore(String type) {
        var mine = (Map<?, ?>) service.ranking(me, type, "FIRST_AC", null, 2026, null, 50).get("myRank");
        return mine == null ? 0L : ((Number) mine.get("score")).longValue();
    }

    @Test void monthlyReportUsesCalendarMonthAndDoesNotExposeIdentifiers() throws Exception {
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)", me, "rank-report@example.test");
        AppUser appUser = new AppUser(); appUser.setId(me); appUser.setRole("STUDENT"); appUser.setStatus("ACTIVE");
        LoginUser login = new LoginUser(appUser);
        Long account = jdbc.queryForObject("select id from platform_account where user_id=?", Long.class, me);
        var month = java.time.YearMonth.from(TimeSupport.today());
        jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'FIRST_AC',900,'SUBMISSION')", me, account, month.atDay(1).minusDays(1));
        mvc.perform(get("/api/stats/monthly-report").param("month", month.toString())).andExpect(status().isUnauthorized());
        var response = mvc.perform(get("/api/stats/monthly-report").with(user(login)).param("month",month.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.newSolved").value(45))
            .andExpect(jsonPath("$.data.activeDays").value(1)).andExpect(jsonPath("$.data.myRank").value(56))
            .andExpect(jsonPath("$.data.from").value(month.atDay(1).toString()))
            .andExpect(jsonPath("$.data.platforms[0].firstAcAvailable").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("rank-55", "rank-report@example.test", "platformUid", "password", "studentNo");
        mvc.perform(get("/api/stats/monthly-report").with(user(login)).param("month", "bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stats/monthly-report").with(user(login)).param("month", month.plusMonths(1).toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stats/ranking").with(user(login)).param("type","WEEK").param("className","一班").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.myRank.rank").value(56));
    }

    @Test void totalMatchesProfileAccountTotalsAndKeepsMappedUniqueCount() {
        jdbc.update("delete from user_solved_problem where user_id=?",me);
        Long firstAccount = jdbc.queryForObject("select id from platform_account where user_id=?", Long.class, me);
        Long secondAccount = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'CODEFORCES','rank-second','VERIFIED') returning id",Long.class,me);
        Long thirdAccount = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'ATCODER','rank-third','VERIFIED') returning id",Long.class,me);
        Long firstProblem = jdbc.queryForObject("insert into problem(platform,problem_key) values ('CODEFORCES','rank-p1') returning id",Long.class);
        Long secondProblem = jdbc.queryForObject("insert into problem(platform,problem_key) values ('ATCODER','rank-p2') returning id",Long.class);
        for (Long account : java.util.List.of(firstAccount,secondAccount)) jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'CODEFORCES')",me,account,firstProblem);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'ATCODER')",me,thirdAccount,secondProblem);
        Long group = jdbc.queryForObject("insert into problem_group(canonical_key) values ('rank-group') returning id",Long.class);
        for (Long problem : java.util.List.of(firstProblem,secondProblem)) jdbc.update("insert into problem_group_member(group_id,problem_id) values (?,?)",group,problem);
        member("rank-total-only",school,"一班",0,"VERIFIED",false);
        var result = service.ranking(me,"TOTAL","FIRST_AC",null,2026,null,50);
        assertThat(((Map<?,?>)result.get("myRank")).get("solved")).isEqualTo(3L);
        assertThat(((Map<?,?>)result.get("myRank")).get("solved")).isEqualTo(statistics.profile(me).get("totalPassed"));
        assertThat(((Map<?,?>)result.get("myRank")).get("uniqueSolved")).isEqualTo(1L);
        assertThat(result.get("participantCount")).isEqualTo(60L);
        var filtered = service.ranking(me,"TOTAL","FIRST_AC","ATCODER",2026,null,50);
        assertThat(filtered.get("participantCount")).isEqualTo(1L);
        assertThat(((Map<?,?>)filtered.get("myRank")).get("solved")).isEqualTo(1L);
    }

    @Test void repeatedProblemsAcrossAccountsDoNotIncreaseWeeklyRankOrMonthlySolved() {
        Long second=jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status,supports_history,history_complete,last_success_at) values (?,'CODEFORCES','rank-overlap','VERIFIED',true,true,now()) returning id",Long.class,me);
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence) select user_id,?,problem_id,platform,earliest_known_ac_at+interval '1 hour',first_ac_confidence from user_solved_problem where user_id=?",second,me);
        jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'FIRST_AC',45,'SUBMISSION')",me,second,TimeSupport.today());
        for (String type : java.util.List.of("WEEK","MONTH")) {
            var mine=(Map<?,?>)service.ranking(me,type,"FIRST_AC",null,2026,null,50).get("myRank");
            assertThat(mine.get("score")).isEqualTo(45L);
            assertThat(mine.get("rank")).isEqualTo(56L);
        }
        var month=java.time.YearMonth.from(TimeSupport.today());
        assertThat(((Number)reports.platforms(me,month.atDay(1),TimeSupport.today()).getFirst().get("solved")).longValue()).isEqualTo(45);
        Long shared=jdbc.queryForObject("select min(problem_id) from user_solved_problem where user_id=?",Long.class,me);
        var accounts=jdbc.queryForList("select id from platform_account where user_id=?",Long.class,me);
        for (Long account : accounts) {
            jdbc.update("insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at) values (?,?,'CODEFORCES',?,'AC',true,now())",account,shared,"rank-sub-"+account);
            jdbc.update("insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source) values (?,?,'CODEFORCES',?,'AC_SUBMISSIONS',1,'SUBMISSION')",me,account,TimeSupport.today());
        }
        assertThat(((Map<?,?>)service.ranking(me,"WEEK","UNIQUE_AC",null,2026,null,50).get("myRank")).get("score")).isEqualTo(1L);
        assertThat(((Map<?,?>)service.ranking(me,"WEEK","AC_SUBMISSIONS",null,2026,null,50).get("myRank")).get("score")).isEqualTo(2L);
        // Move the original AC before the window: its recent repeat must not count as new.
        jdbc.update("update user_solved_problem set earliest_known_ac_at=? where user_id=? and account_id<>?",java.sql.Timestamp.from(TimeSupport.campusDayStart(month.atDay(1).minusDays(40))),me,second);
        sqlSession.clearCache(); // JDBC fixture updates bypass MyBatis cache invalidation.
        assertThat(((Map<?,?>)service.ranking(me,"WEEK","FIRST_AC",null,2026,null,50).get("myRank")).get("score")).isEqualTo(0L);
        assertThat(((Number)reports.platforms(me,month.atDay(1),TimeSupport.today()).getFirst().get("solved")).longValue()).isZero();
    }

    @Test void snapshotOnlyReportHasUnavailableMetricsNotInventedZeroes() throws Exception {
        Long snapshotUser = member("rank-only-snapshot",school,"一班",0,"VERIFIED",false);
        jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",snapshotUser,"snapshot-report@example.test");
        AppUser appUser = new AppUser(); appUser.setId(snapshotUser); appUser.setRole("STUDENT"); appUser.setStatus("ACTIVE");
        mvc.perform(get("/api/stats/monthly-report").with(user(new LoginUser(appUser))).param("month",java.time.YearMonth.from(TimeSupport.today()).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.newSolved").isEmpty())
            .andExpect(jsonPath("$.data.activeDays").isEmpty()).andExpect(jsonPath("$.data.myRank").isEmpty());
    }

    /** 没上榜时说出挡住的那一条，而不是笼统地让人去查年级、班级、隐私和平台 */
    @Test void explainsWhyViewerIsNotRanked() {
        Long hidden = member("rank-why-hidden", school, "一班", 5, "VERIFIED", true);
        jdbc.update("insert into user_privacy(user_id,show_on_ranking) values (?,false)", hidden);
        assertThat(note(hidden, null, null, null)).contains("关闭参与排行");
        assertThat(reason(hidden, null, null, null)).isEqualTo("HIDDEN");

        Long privateProfile = member("rank-why-private", school, "一班", 5, "VERIFIED", true);
        jdbc.update("insert into user_privacy(user_id,profile_visibility) values (?,'PRIVATE')", privateProfile);
        assertThat(reason(privateProfile, null, null, null)).isEqualTo("PRIVATE_PROFILE");

        Long claimable = member("rank-why-claimable", school, "一班", 5, "RESOLVED", true);
        assertThat(note(claimable, null, null, null))
            .contains("还没有验证过归属的账号").contains("Codeforces 账号还是待认领");
        assertThat(reason(claimable, null, null, null)).isEqualTo("NO_VERIFIED");

        assertThat(note(me, 2025, null, null)).isEqualTo("你是 2026 级，不在当前年级筛选范围内");
        assertThat(reason(me, 2025, null, null)).isEqualTo("YEAR");
        assertThat(note(me, null, "二班", null)).isEqualTo("你不在当前班级筛选范围内");
        assertThat(reason(me, null, "二班", null)).isEqualTo("CLASS");
        assertThat(note(me, null, null, "ATCODER")).isEqualTo("你在 AtCoder 没有验证过归属的账号");
        assertThat(reason(me, null, null, "ATCODER")).isEqualTo("PLATFORM");

        Long noHistory = member("rank-why-metric", school, "一班", 5, "VERIFIED", false);
        assertThat(note(noHistory, null, null, null))
            .isEqualTo("你验证过归属的 Codeforces 账号这段时间没有能确定首次通过时间的记录");
        assertThat(reason(noHistory, null, null, null)).isEqualTo("NO_DATA");
        var ranked = service.ranking(me, "WEEK", "FIRST_AC", null, 2026, null, 50);
        assertThat(ranked.get("myRankNote")).isEqualTo("同分并列；名次基于当前筛选范围内全部参与者计算");
        assertThat(ranked).containsEntry("myRankReason", null);
    }

    /** 年级、班级筛选的选项只列参与排行的同学所在的：别的学校、关了参与排行的不算，班级按所选年级给 */
    @Test void offersYearAndClassOptionsFromParticipants() {
        member("rank-option-b", school, "二班", 5, "VERIFIED", true);
        Long older = member("rank-option-2025", school, "旧一班", 5, "VERIFIED", true);
        jdbc.update("update school_member set enrollment_year = 2025 where user_id = ?", older);
        Long hidden = member("rank-option-hidden", school, "三班", 5, "VERIFIED", true);
        jdbc.update("insert into user_privacy(user_id,show_on_ranking) values (?,false)", hidden);
        Long otherSchool = jdbc.queryForObject("insert into school(name,invite_code) values ('选项外校','ranking-option-other') returning id", Long.class);
        member("rank-option-outside", otherSchool, "外校班", 5, "VERIFIED", true);

        var in2026 = service.ranking(me, "WEEK", "FIRST_AC", null, 2026, null, 50);
        assertThat(in2026.get("yearOptions")).isEqualTo(java.util.List.of(2026, 2025));
        assertThat(in2026.get("classOptions")).isEqualTo(java.util.List.of("一班", "二班"));
        assertThat(service.ranking(me, "TOTAL", "FIRST_AC", null, 2025, null, 50).get("classOptions"))
            .isEqualTo(java.util.List.of("旧一班"));
        assertThat(service.ranking(me, "WEEK", "FIRST_AC", null, null, null, 50).get("classOptions"))
            .isEqualTo(java.util.List.of("一班", "二班", "旧一班"));
    }

    private String note(Long user, Integer year, String className, String platform) {
        Map<String, Object> result = service.ranking(user, "WEEK", "FIRST_AC", platform, year, className, 50);
        assertThat(result.get("myRank")).isNull();
        return (String) result.get("myRankNote");
    }

    private String reason(Long user, Integer year, String className, String platform) {
        return (String) service.ranking(user, "WEEK", "FIRST_AC", platform, year, className, 50).get("myRankReason");
    }
}
