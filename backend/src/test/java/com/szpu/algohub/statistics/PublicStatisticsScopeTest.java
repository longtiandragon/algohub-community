package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.activity.AchievementService;
import com.szpu.algohub.activity.ActivityService;

/** Unverified sources must be removed before aggregation and per-problem de-duplication. */
@SpringBootTest
@Transactional
class PublicStatisticsScopeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSession sqlSession;
    @Autowired StatisticsService statistics;
    @Autowired AchievementService achievements;
    @Autowired ActivityService activity;
    @Autowired DailyStatMapper daily;
    @Autowired CampusRankingService ranking;

    private Long user;
    private Long verified;
    private final LocalDate from = LocalDate.of(2026, 9, 18);
    private final LocalDate to = LocalDate.of(2026, 9, 20);

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString();
        user = jdbc.queryForObject("""
                insert into app_user(username,password_hash,display_name,role,status)
                values (?,'x','public-scope','STUDENT','ACTIVE') returning id
                """, Long.class, "public-scope-" + suffix);
        jdbc.update("insert into user_privacy(user_id,profile_visibility,show_accounts) values (?,'PUBLIC',false)", user);
        verified = account("CODEFORCES", "verified-" + suffix, "VERIFIED");
        // V35 derives the user avatar from the selected verified platform account.
        jdbc.update("update platform_account set avatar_url='https://userpic.codeforces.org/123/avatar.jpg' where id=?", verified);
        jdbc.update("update app_user set avatar_account_id=? where id=?", verified, user);
        Long pending = account("CODEFORCES", "pending-" + suffix, "UNVERIFIED");
        Long watched = account("ATCODER", "watched-" + suffix, "RESOLVED");
        Long shared = problem("CODEFORCES", "public-" + suffix, 1000, "scope-public", "SCOPE_PUBLIC");
        Long privateProblem = problem("CODEFORCES", "pending-" + suffix, 2400, "scope-pending", "SCOPE_PENDING");
        Long watchedProblem = problem("ATCODER", "watched-" + suffix, 1800, "scope-watched", "SCOPE_WATCHED");
        solve(verified, shared, "CODEFORCES", "2026-09-20T00:00:00Z");
        solve(pending, shared, "CODEFORCES", "2026-09-18T00:00:00Z");
        solve(pending, privateProblem, "CODEFORCES", "2026-09-19T00:00:00Z");
        solve(watched, watchedProblem, "ATCODER", "2026-09-19T00:00:00Z");
        for (Long account : List.of(verified, pending, watched)) {
            daily.rebuildSubmissions(account);
            daily.rebuildAcSubmissions(account);
            daily.rebuildUniqueAc(account);
            daily.rebuildFirstAc(account);
            jdbc.update("insert into total_snapshot(account_id,solved_total,source) values (?,100,'SYNC')", account);
        }
    }

    @Test void everyPublicProfileAggregateAndRecentRecordUsesOnlyVerifiedAccounts() {
        var own = statistics.profile(user);
        var publicProfile = statistics.profileForOthers(user, false);
        assertThat(number(own, "totalSolvedAcrossPlatforms")).isEqualTo(3);
        assertThat(number(publicProfile, "totalSolvedAcrossPlatforms")).isEqualTo(1);
        assertThat(maps(publicProfile, "platformSolved")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("platform", "CODEFORCES").containsEntry("solved", 1L));
        assertThat(number(map(publicProfile, "crossPlatformMapping"), "uniqueSolvedAfterMapping")).isEqualTo(1);
        assertThat(maps(publicProfile, "difficultyDistribution")).singleElement()
                .satisfies(row -> assertThat(row.get("difficulty")).isEqualTo("1000"));
        assertThat(number(map(publicProfile, "tagCoverage"), "total")).isEqualTo(1);
        assertThat(maps(publicProfile, "tagDistribution")).extracting(row -> row.get("category"))
                .containsExactly("SCOPE_PUBLIC");
        assertThat(maps(own, "snapshotOverview")).hasSize(3);
        assertThat(maps(publicProfile, "snapshotOverview")).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKeys("handle", "rawExtra", "authMode", "ownershipStatus"));
        assertThat(maps(publicProfile, "recentAccepted")).singleElement()
                .satisfies(row -> assertThat(row.get("problemKey").toString()).startsWith("public-"));
    }

    @Test void heatmapFiltersAllSourcesBeforeDistinctProblemAggregation() {
        var rows = daily.heatmap(user, from, to, null, true);
        assertThat(rows).hasSize(4).allSatisfy(row -> {
            assertThat(row.get("statDate").toString()).isEqualTo("2026-09-20");
            assertThat(number(row, "value")).isEqualTo(1);
        });
        assertThat(daily.sumMetric(user, "AC_SUBMISSIONS", from, to, false)).isEqualTo(4);
        assertThat(daily.sumMetric(user, "AC_SUBMISSIONS", from, to, true)).isEqualTo(1);
        assertThat(map(statistics.heatmap(user, from, to, null, true), "metrics")).hasSize(4);
    }

    @Test void publicAchievementsAndStreakIgnoreWatchedAndPendingHistory() {
        var own = achievements.achievements(user);
        var visible = achievements.achievements(user, true);
        assertThat(achievement(own, "SOLVE_10").progress()).isEqualTo(3);
        assertThat(achievement(visible, "SOLVE_10").progress()).isEqualTo(1);
        assertThat(achievement(own, "PLATFORM_2").progress()).isEqualTo(2);
        assertThat(achievement(visible, "PLATFORM_2").progress()).isEqualTo(1);
        assertThat(maps(visible, "difficultyPeaks")).singleElement()
                .satisfies(row -> assertThat(number(row, "maxDifficulty")).isEqualTo(1000));
        assertThat(maps(visible, "categoryCoverage")).extracting(row -> row.get("category"))
                .containsExactly("SCOPE_PUBLIC");
        assertThat(activity.streak(user).get("activeDays")).isEqualTo(2);
        assertThat(activity.streak(user, true).get("activeDays")).isEqualTo(1);
    }

    @Test void revokingOwnershipImmediatelyRemovesAllPublicStatistics() {
        statistics.profileForOthers(user, true);
        jdbc.update("update platform_account set ownership_status='RESOLVED' where id=?", verified);
        sqlSession.clearCache();
        var visible = statistics.profileForOthers(user, true);
        assertThat(number(visible, "totalSolvedAcrossPlatforms")).isZero();
        assertThat(maps(visible, "recentAccepted")).isEmpty();
        assertThat(maps(visible, "snapshotOverview")).isEmpty();
        assertThat(daily.heatmap(user, from, to, null, true)).isEmpty();
        assertThat(achievement(achievements.achievements(user, true), "SOLVE_10").progress()).isZero();
        assertThat(activity.streak(user, true).get("activeDays")).isEqualTo(0);
        assertThat(number(statistics.profile(user), "totalSolvedAcrossPlatforms")).isEqualTo(3);
    }

    @Test void globalRankingHonorsTheHiddenPlatformIdentityAvatarSetting() {
        var rows = maps(ranking.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", "CODEFORCES", null, null, 200), "rows");
        assertThat(rows.stream().filter(row -> number(row, "userId") == user).toList()).singleElement()
                .satisfies(row -> assertThat(row.get("avatarUrl")).isNull());
        jdbc.update("update user_privacy set show_accounts=true where user_id=?", user);
        sqlSession.clearCache();
        var visibleRows = maps(ranking.ranking(user, "GLOBAL", "TOTAL", "FIRST_AC", "CODEFORCES", null, null, 200), "rows");
        assertThat(visibleRows.stream().filter(row -> number(row, "userId") == user).toList()).singleElement()
                .satisfies(row -> assertThat(row.get("avatarUrl")).isEqualTo("https://userpic.codeforces.org/123/avatar.jpg"));
    }

    @Test void duplicateSolvesAcrossVerifiedAccountsCannotUnlockTheNextMilestone() {
        Long duplicate = account("CODEFORCES", "duplicate-" + UUID.randomUUID(), "VERIFIED");
        Long shared = jdbc.queryForObject("select problem_id from user_solved_problem where account_id=?", Long.class, verified);
        solve(duplicate, shared, "CODEFORCES", "2026-09-20T01:00:00Z");
        for (int i = 0; i < 8; i++) {
            Long problem = problem("CODEFORCES", "milestone-" + UUID.randomUUID(), 1000, "scope-public", "SCOPE_PUBLIC");
            solve(verified, problem, "CODEFORCES", "2026-09-20T00:00:00Z");
        }
        sqlSession.clearCache();
        var visible = achievements.achievements(user, true);
        assertThat(achievement(visible, "SOLVE_10").progress()).isEqualTo(9);
        assertThat(achievement(visible, "SOLVE_10").unlocked()).isFalse();
        assertThat(achievement(visible, "SOLVE_10").progressText()).isEqualTo("9 / 10");
        assertThat(number(statistics.profileForOthers(user, true), "totalSolvedAcrossPlatforms")).isEqualTo(9);
        assertThat(maps(visible, "difficultyPeaks")).singleElement()
                .satisfies(row -> assertThat(number(row, "ratedSolved")).isEqualTo(9));
    }

    private Long account(String platform, String handle, String ownership) {
        return jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,?,?,?) returning id",
                Long.class, user, platform, handle, ownership);
    }

    private Long problem(String platform, String key, int difficulty, String tag, String category) {
        Long id = jdbc.queryForObject("""
                insert into problem(platform,problem_key,title,difficulty_value,difficulty_label,difficulty_source)
                values (?,?,?, ?,?,'OFFICIAL') returning id
                """, Long.class, platform, key, key, difficulty, Integer.toString(difficulty));
        jdbc.update("insert into problem_tag(problem_id,tag_raw) values (?,?)", id, tag);
        jdbc.update("insert into tag_category_mapping(platform,tag_raw,category) values (?,?,?) on conflict(platform,tag_raw) do update set category=excluded.category", platform, tag, category);
        return id;
    }

    private void solve(Long account, Long problem, String platform, String at) {
        Timestamp time = Timestamp.from(Instant.parse(at));
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence) values (?,?,?,?,?,'EXACT')",
                user, account, problem, platform, time);
        jdbc.update("insert into submission(account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at,contest_ref) values (?,?,?,?,'AC',true,?,?)",
                account, problem, platform, "scope-" + problem, time, "contest-" + problem);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
    @SuppressWarnings("unchecked") private static AchievementService.Achievement achievement(Map<String, Object> result, String code) {
        return ((List<AchievementService.Achievement>) result.get("achievements")).stream()
                .filter(row -> row.code().equals(code)).findFirst().orElseThrow();
    }
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
