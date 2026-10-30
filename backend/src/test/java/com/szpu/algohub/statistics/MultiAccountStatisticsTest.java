package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.activity.ActivityMapper;

@SpringBootTest
@Transactional
class MultiAccountStatisticsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StatisticsMapper mapper;
    @Autowired ActivityMapper activity;
    @Autowired com.szpu.algohub.activity.ActivityService activityService;
    @Autowired DailyStatMapper daily;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @Autowired CampusRankingService ranking;

    @Test void repeatedAcDoesNotCreateAnotherFirstAcOrFeedEvent() {
        Long user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('stats-daily','x','每日去重测试','STUDENT','ACTIVE') returning id",Long.class);
        Long school=joinSchool(user);
        Long first=account(user,"daily-first"), second=account(user,"daily-second");
        Long shared=problem("daily-shared");
        solve(user,first,shared);
        solve(user,second,shared);
        var earlier=java.time.Instant.parse("2026-09-19T16:01:00Z");
        var later=earlier.plusSeconds(86400);
        jdbc.update("update user_solved_problem set earliest_known_ac_at=?,first_ac_confidence='EXACT' where account_id=?",java.sql.Timestamp.from(earlier),first);
        jdbc.update("update user_solved_problem set earliest_known_ac_at=?,first_ac_confidence='EXACT' where account_id=?",java.sql.Timestamp.from(later),second);
        for (Long account : java.util.List.of(first,second)) {
            jdbc.update("insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at) values (?,?,'CODEFORCES',?,'AC',true,?)",account,shared,"daily-"+account,java.sql.Timestamp.from(later));
            daily.rebuildAcSubmissions(account);
            daily.rebuildFirstAc(account);
            daily.rebuildUniqueAc(account);
        }
        var from=java.time.LocalDate.of(2026,9,20);
        var to=from.plusDays(1);
        var rows=daily.heatmap(user,from,to,null);
        assertThat(rows.stream().filter(r -> r.get("metric").equals("FIRST_AC")).toList())
                .singleElement().satisfies(r -> {
                    assertThat(number(r,"value")).isEqualTo(1);
                    assertThat(r.get("statDate").toString()).isEqualTo("2026-09-20");
                });
        assertThat(daily.sumMetric(user,"UNIQUE_AC",from,to)).isEqualTo(1);
        assertThat(daily.sumMetric(user,"AC_SUBMISSIONS",from,to)).isEqualTo(2);
        assertThat(daily.sumMetric(user,"FIRST_AC",to,to)).isZero();
        assertThat(daily.heatmap(user,from,to,"ATCODER")).isEmpty();
        var feed=activity.schoolFeed(school,null,"CODEFORCES",user,null,null,10);
        assertThat(feed).hasSize(1);
        assertThat(((java.sql.Timestamp)feed.get(0).get("acAt")).toInstant()).isEqualTo(earlier);
        assertThat(activity.dailySolveDays(user)).singleElement()
                .satisfies(r -> assertThat(number(r,"solved")).isEqualTo(1));
        // An incomplete second account must not turn a known time into an exact first AC.
        jdbc.update("update user_solved_problem set first_ac_confidence='EARLIEST_KNOWN' where account_id=?",second);
        sqlSession.clearCache();
        assertThat(daily.sumMetric(user,"FIRST_AC",from,to)).isZero();
        assertThat(activity.schoolFeed(school,null,"CODEFORCES",user,null,null,10).getFirst().get("firstAcConfidence"))
                .isEqualTo("EARLIEST_KNOWN");
        jdbc.update("update user_solved_problem set earliest_known_ac_at=null where account_id=?",second);
        sqlSession.clearCache();
        assertThat(activity.schoolFeed(school,null,"CODEFORCES",user,null,null,10).getFirst().get("firstAcConfidence"))
                .isEqualTo("EARLIEST_KNOWN");
    }

    @Test void overlappingAccountsCountOnceInProfileButSeparatelyOnAccountCards() {
        Long user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('stats-overlap','x','重复题测试','STUDENT','ACTIVE') returning id",Long.class);
        Long first=account(user,"stats-first");
        Long second=account(user,"stats-second");
        Long shared=problem("stats-shared");
        Long extra=problem("stats-extra");
        solve(user,first,shared);
        solve(user,second,shared);
        solve(user,second,extra);
        jdbc.update("insert into problem_tag(problem_id,tag_raw) values (?,'dp')",shared);

        assertThat(number(mapper.platformSolved(user).get(0),"solved")).isEqualTo(2);
        var summary=mapper.uniqueSolvedSummary(user);
        assertThat(number(summary,"totalSolved")).isEqualTo(2);
        assertThat(number(summary,"uniqueSolved")).isEqualTo(2);
        assertThat(number(mapper.difficultyDistribution(user).get(0),"solved")).isEqualTo(2);
        assertThat(number(mapper.tagCoverage(user),"total")).isEqualTo(2);
        assertThat(number(mapper.tagCoverage(user),"withTags")).isEqualTo(1);
        assertThat(number(mapper.tagDistribution(user).get(0),"solved")).isEqualTo(1);
        joinSchool(user);
        @SuppressWarnings("unchecked")
        var rows=(java.util.List<Map<String,Object>>) ranking.ranking(user,"TOTAL","FIRST_AC","CODEFORCES",null,null,200).get("rows");
        var rank=rows.stream().filter(row -> number(row,"userId")==user).findFirst().orElseThrow();
        assertThat(number(rank,"solved")).isEqualTo(2);
        assertThat(activity.ownCoverage(user)).extracting(row -> number(row,"solvedCount"))
                .containsExactlyInAnyOrder(1L,2L);
    }

    @Test void lifetimeStreakDoesNotStopAtFourHundredActiveDays() {
        Long user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('stats-long-history','x','长期历史测试','STUDENT','ACTIVE') returning id",Long.class);
        Long account=account(user,"long-history");
        jdbc.update("insert into problem(platform,problem_key) select 'CODEFORCES','long-history-' || n from generate_series(0,449) n");
        jdbc.update("""
            insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence)
            select ?,?,p.id,'CODEFORCES',
                   ((cast(? as date)-n)::timestamp at time zone 'Asia/Shanghai'),'EXACT'
            from generate_series(0,449) n join problem p
              on p.platform='CODEFORCES' and p.problem_key='long-history-' || n
            """,user,account,com.szpu.algohub.common.TimeSupport.today());
        var streak=activityService.streak(user);
        assertThat(streak.get("currentStreak")).isEqualTo(450);
        assertThat(streak.get("longestStreak")).isEqualTo(450);
        assertThat(streak.get("activeDays")).isEqualTo(450);
        assertThat(streak.get("solvedToday")).isEqualTo(1);
    }

    private Long joinSchool(Long user) {
        Long school=jdbc.queryForObject("select id from school order by id limit 1",Long.class);
        jdbc.update("insert into school_member(school_id,user_id,status) values (?,?,'APPROVED')",school,user);
        return school;
    }
    private Long account(Long user,String handle) {
        return jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'CODEFORCES',?,'VERIFIED') returning id",Long.class,user,handle);
    }
    private Long problem(String key) {
        return jdbc.queryForObject("insert into problem(platform,problem_key) values ('CODEFORCES',?) returning id",Long.class,key);
    }
    private void solve(Long user,Long account,Long problem) {
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'CODEFORCES')",user,account,problem);
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
