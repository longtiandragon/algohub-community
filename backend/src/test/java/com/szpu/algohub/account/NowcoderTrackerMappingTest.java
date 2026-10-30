package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 牛客 Tracker 去重：题号相同且标题一致才算同一题、合并不重复计；题号撞了但标题不同另记，不合并也不丢；
 * 映射变了（竞赛题库给同一题号换了标题）时原来那条撤掉、按新映射另记，总数不变。
 */
@SpringBootTest
class NowcoderTrackerMappingTest {

    @Autowired
    private NowcoderTrackerController tracker;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        cleanProblems();
        userId = jdbc.queryForObject("insert into app_user (username, password_hash, display_name) values (?, 'x', 'tracker') returning id",
                Long.class, "tracker-" + UUID.randomUUID());
        accountId = jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status)
                values (?, 'NOWCODER', ?, 'tracker', 'RESOLVED') returning id
                """, Long.class, userId, "trk-" + UUID.randomUUID());
        // 竞赛题库里已同步到的两道通过题
        acmSolved("99001", "A 题");
        acmSolved("99003", "C 原题");
        // Tracker 通过的三道：一道同题（只差大小写与空白）、一道只在 Tracker、一道题号撞了但标题不同
        trackerAccepted("99001", "a题");
        trackerAccepted("99002", "B");
        trackerAccepted("99003", "完全不同的一道题");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_user where id = ?", userId);
        cleanProblems();
    }

    private void cleanProblems() {
        jdbc.execute("delete from user_solved_problem where problem_id in (select id from problem where problem_key like '%9900%')");
        jdbc.execute("delete from problem where platform = 'NOWCODER' and problem_key like '%9900%'");
    }

    private void acmSolved(String pid, String title) {
        Long problemId = jdbc.queryForObject("""
                insert into problem (platform, problem_key, title, url) values ('NOWCODER', ?, ?, ?) returning id
                """, Long.class, pid, title, "https://ac.nowcoder.com/acm/problem/" + pid);
        jdbc.update("""
                insert into user_solved_problem (user_id, account_id, problem_id, platform, earliest_known_ac_at,
                                                 first_ac_confidence, source)
                values (?, ?, ?, 'NOWCODER', now(), 'EXACT', 'SYNC')
                """, userId, accountId, problemId);
    }

    private void trackerAccepted(String pid, String title) {
        jdbc.update("insert into nowcoder_tracker_problem (account_id, pid, title, url, difficulty) values (?, ?, ?, ?, '800')",
                accountId, pid, title, "https://www.nowcoder.com/practice/" + pid);
    }

    private List<String> trackerKeys() {
        return jdbc.queryForList("""
                select p.problem_key from user_solved_problem s join problem p on p.id = s.problem_id
                 where s.account_id = ? and s.source = 'TRACKER' order by p.problem_key
                """, String.class, accountId);
    }

    private PlatformAccount mine() {
        return accountService.listMine(userId).stream().filter(a -> a.getId().equals(accountId)).findFirst().orElseThrow();
    }

    @Test
    void sameProblemMergesOnceAndTitleConflictsAreKeptSeparately() {
        tracker.mergeCachedSolved();

        assertThat(trackerKeys()).containsExactly("99002", "tracker-99003");
        assertThat(jdbc.queryForList("select difficulty_label from problem where platform='NOWCODER' and problem_key='99002'", String.class))
                .containsExactly("800");
        PlatformAccount account = mine();
        // 99001 合并进竞赛题，99003 竞赛题与 tracker-99003 各记一道，99002 只在 Tracker
        assertThat(account.getCollectedProblems()).isEqualTo(4);
        assertThat(account.getTrackerProblems()).isEqualTo(2);
        assertThat(account.getTrackerAccepted()).isEqualTo(3);
        assertThat(account.getTrackerMerged()).isEqualTo(1);
        assertThat(account.getTrackerConflicts()).isEqualTo(1);

        // 再合并一次：结果不变
        tracker.mergeCachedSolved();
        assertThat(trackerKeys()).containsExactly("99002", "tracker-99003");
        assertThat(mine().getCollectedProblems()).isEqualTo(4);
    }

    @Test
    void trackerBackfillsAndRevisesScoresWithoutOverwritingOfficialCatalogScores() {
        tracker.mergeCachedSolved();
        assertThat(jdbc.queryForObject("select difficulty_value from problem where platform='NOWCODER' and problem_key='99001'", Integer.class))
                .isEqualTo(800);
        jdbc.update("update nowcoder_tracker_problem set difficulty='1600' where account_id=?", accountId);
        tracker.mergeCachedSolved();
        assertThat(jdbc.queryForObject("select difficulty_value from problem where platform='NOWCODER' and problem_key='99001'", Integer.class))
                .isEqualTo(1600);
        jdbc.update("update nowcoder_tracker_problem set difficulty='-1' where account_id=?", accountId);
        tracker.mergeCachedSolved();
        assertThat(jdbc.queryForObject("select difficulty_value from problem where platform='NOWCODER' and problem_key='99001'", Integer.class))
                .isEqualTo(1600);
        jdbc.update("update problem set difficulty_raw='1800',difficulty_label='1800',difficulty_value=1800,catalog_synced_at=now() where platform='NOWCODER' and problem_key='99001'");
        jdbc.update("update nowcoder_tracker_problem set difficulty='800' where account_id=?", accountId);
        tracker.mergeCachedSolved();
        assertThat(jdbc.queryForObject("select difficulty_value from problem where platform='NOWCODER' and problem_key='99001'", Integer.class))
                .isEqualTo(1800);
    }

    @Test
    void whenTheContestLibraryLaterGivesTheIdAnotherTitleTheTrackerSolveMovesInsteadOfDoubling() {
        tracker.mergeCachedSolved();
        // 竞赛题库同步后，99002 这个题号原来是另一道题
        jdbc.update("update problem set title = 'B 其实是另一道题' where platform = 'NOWCODER' and problem_key = '99002'");

        tracker.mergeCachedSolved();

        assertThat(trackerKeys()).containsExactly("tracker-99002", "tracker-99003");
        PlatformAccount account = mine();
        assertThat(account.getCollectedProblems()).isEqualTo(4);
        assertThat(account.getTrackerConflicts()).isEqualTo(2);
    }
}
