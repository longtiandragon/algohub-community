package com.szpu.algohub.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.common.ApiException;

/**
 * 比赛收藏与 .ics 导出的口径验证：
 * - 收藏幂等，且只看得到自己的收藏；
 * - 比赛信息实时取自 contest 表，改期后导出跟着变；
 * - 源站没给结束时间与时长时**不写 DTEND**，不拿「默认两小时」糊上去；
 * - 标题里的逗号、分号、反斜杠按 RFC 5545 转义，中文折行不截断成乱码；
 * - 导出说明里写明这是快照，不会自动更新。
 */
@SpringBootTest
class ContestCalendarTest {

    @Autowired
    private ContestService contestService;
    @Autowired
    private ContestMapper contestMapper;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from contest_bookmark");
        jdbcTemplate.execute("delete from contest");
        jdbcTemplate.update("delete from app_user where username like 'cal-test%'");
        userA = newUser("cal-test-a-" + System.nanoTime());
        userB = newUser("cal-test-b-" + System.nanoTime());
    }

    private Long newUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName("日历测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return user.getId();
    }

    private Long contest(String key, String title, Instant startAt, Instant endAt, Integer durationSec) {
        Contest contest = new Contest();
        contest.setPlatform("CODEFORCES");
        contest.setContestKey(key);
        contest.setTitle(title);
        contest.setStartAt(startAt);
        contest.setEndAt(endAt);
        contest.setDurationSec(durationSec);
        contest.setStatus("SCHEDULED");
        contest.setUrl("https://codeforces.com/contest/" + key);
        contest.setSource("OFFICIAL");
        contestMapper.upsert(contest);
        return jdbcTemplate.queryForObject(
                "select id from contest where platform='CODEFORCES' and contest_key=?", Long.class, key);
    }

    private String ics(Long userId) {
        return new String(contestService.calendar(userId), StandardCharsets.UTF_8);
    }

    /**
     * 去掉 RFC 5545 的折行再断言。长句子会被折成多行（续行以空格开头），
     * 直接在原文里找完整句子会误判成“内容缺失”。
     */
    private String unfolded(Long userId) {
        return ics(userId).replace("\r\n ", "");
    }

    @Test
    void favoritingIsIdempotentAndPrivateToEachUser() {
        Long id = contest("2000", "Round 900", Instant.now().plus(Duration.ofDays(2)), null, 7200);

        contestService.favorite(userA, id);
        contestService.favorite(userA, id);

        assertThat(contestService.favoritedIds(userA)).containsExactly(id);
        assertThat(contestService.favoritedIds(userB)).isEmpty();
        assertThat(contestService.favorited(userB)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from contest_bookmark", Long.class))
                .isEqualTo(1);
    }

    @Test
    void unfavoritingRemovesOnlyMyOwnRow() {
        Long id = contest("2001", "Round 901", Instant.now().plus(Duration.ofDays(1)), null, 7200);
        contestService.favorite(userA, id);
        contestService.favorite(userB, id);

        contestService.unfavorite(userA, id);

        assertThat(contestService.favoritedIds(userA)).isEmpty();
        assertThat(contestService.favoritedIds(userB)).containsExactly(id);
    }

    @Test
    void favoritingAContestThatIsGoneIsReported() {
        assertThatThrownBy(() -> contestService.favorite(userA, 999999L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("找不到这场比赛");
    }

    @Test
    void exportingWithoutAnyFavoriteIsRejected() {
        assertThatThrownBy(() -> contestService.calendar(userA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("还没有收藏任何比赛");
    }

    @Test
    void icsCarriesStartEndUrlAndTheSnapshotCaveat() {
        Instant start = Instant.parse("2026-10-01T12:00:00Z");
        Long id = contest("2002", "Round 902", start, start.plus(Duration.ofHours(2)), null);
        contestService.favorite(userA, id);

        String text = ics(userA);

        assertThat(text).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(text).contains("DTSTART:20261001T120000Z");
        assertThat(text).contains("DTEND:20261001T140000Z");
        assertThat(text).contains("UID:algohub-contest-" + id + "@szpu-algohub.local");
        assertThat(text).contains("codeforces.com/contest/2002");
        assertThat(text).contains("TRIGGER:-PT30M");
        assertThat(unfolded(userA)).contains("这是导出当时的快照");
        assertThat(unfolded(userA)).contains("请重新导出");
        assertThat(text).endsWith("END:VCALENDAR\r\n");
        // 每一行都必须是 CRLF 结尾，否则部分日历软件直接拒绝导入
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void durationIsUsedWhenTheSourceOnlyGivesLength() {
        Instant start = Instant.parse("2026-10-02T10:00:00Z");
        Long id = contest("2003", "Round 903", start, null, 5400);
        contestService.favorite(userA, id);

        assertThat(ics(userA)).contains("DTEND:20261002T113000Z");
    }

    @Test
    void withoutEndOrDurationNoEndTimeIsInvented() {
        Instant start = Instant.parse("2026-10-03T10:00:00Z");
        Long id = contest("2004", "Round 904", start, null, null);
        contestService.favorite(userA, id);

        String text = ics(userA);

        assertThat(text).doesNotContain("DTEND:");
        assertThat(unfolded(userA)).contains("源站没有给出结束时间与时长");
    }

    @Test
    void textFieldsAreEscapedSoOneCommaCannotBreakTheFile() {
        Instant start = Instant.parse("2026-10-04T10:00:00Z");
        Long id = contest("2005", "Round 905, Div. 2; part\\one", start, null, 7200);
        contestService.favorite(userA, id);

        String text = ics(userA);

        assertThat(text).contains("Round 905\\, Div. 2\\; part\\\\one");
    }

    @Test
    void longChineseTitlesAreFoldedWithoutBreakingCharacters() {
        Instant start = Instant.parse("2026-10-05T10:00:00Z");
        String title = "深圳职业技术大学校内算法训练赛第一场超长标题用于验证折行不会把汉字劈成乱码继续加长再加长";
        Long id = contest("2006", title, start, null, 7200);
        contestService.favorite(userA, id);

        String text = ics(userA);

        // 折行后把续行的前导空格去掉，应当还原出完整标题
        String unfolded = text.replace("\r\n ", "");
        assertThat(unfolded).contains(title);
        assertThat(text).doesNotContain("�");
        for (String line : text.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    @Test
    void platformPrefixIsNotRepeatedWhenTheTitleAlreadyHasIt() {
        Instant start = Instant.parse("2026-10-08T10:00:00Z");
        Long withPrefix = contest("2008", "Codeforces Round 1121 (Div. 2)", start, null, 7200);
        Long without = contest("2009", "Round 1122", start, null, 7200);
        contestService.favorite(userA, withPrefix);
        contestService.favorite(userA, without);

        String text = unfolded(userA);

        assertThat(text).contains("SUMMARY:Codeforces Round 1121 (Div. 2)");
        assertThat(text).doesNotContain("Codeforces Codeforces");
        assertThat(text).contains("SUMMARY:Codeforces Round 1122");
    }

    @Test
    void rescheduledContestShowsTheNewTimeOnNextExport() {
        Instant start = Instant.parse("2026-10-06T10:00:00Z");
        Long id = contest("2007", "Round 907", start, null, 7200);
        contestService.favorite(userA, id);
        assertThat(ics(userA)).contains("DTSTART:20261006T100000Z");

        // 源站改期：同一个 contest_key 再次入库
        contest("2007", "Round 907", Instant.parse("2026-10-07T11:00:00Z"), null, 7200);

        assertThat(ics(userA)).contains("DTSTART:20261007T110000Z");
        assertThat(contestService.favoritedIds(userA)).containsExactly(id);
    }
}
