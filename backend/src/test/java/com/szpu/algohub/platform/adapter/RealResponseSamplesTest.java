package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 用脱敏后的真实响应（{@code src/test/resources/samples}，说明见同目录 README）回放各平台解析：
 * 正常、零题、查无此人、登录失效、防护页、字段变化与重复分页。重点是"不知道"不能变成 0、异常页面不能入库。
 */
class RealResponseSamplesTest {

    private final PlatformHttpClient http = mock(PlatformHttpClient.class);

    static String sample(String path) {
        try (InputStream in = RealResponseSamplesTest.class.getResourceAsStream("/samples/" + path)) {
            if (in == null) {
                throw new IllegalStateException("缺少样本 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SyncContext context(String handle, String uid, String credential) {
        return new SyncContext(1L, handle, uid, null, credential, null);
    }

    private static void fails(ThrowingCallable call, ErrorKind kind, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getKind()).isEqualTo(kind);
            assertThat(e.getMessage()).contains(message);
        });
    }

    // ---- Codeforces ----

    private SubmissionPage codeforces(String file) {
        when(http.get(eq(Platform.CODEFORCES), contains("user.status"))).thenReturn(sample(file));
        return new CodeforcesAdapter(http).fetchSubmissions(context("sample_user", "1", null));
    }

    @Test
    void codeforcesNormalPageKeepsIdsTimesAndVerdicts() {
        SubmissionPage page = codeforces("codeforces/status-normal.json");
        assertThat(page.submissions()).hasSize(3);
        assertThat(page.submissions().getFirst().platformSubId()).isEqualTo("391528818");
        assertThat(page.submissions().getFirst().submittedAt()).isEqualTo(Instant.ofEpochSecond(1790008882));
        assertThat(page.submissions().getFirst().accepted()).isTrue();
        assertThat(page.reachedHistoryStart()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("391528818");
    }

    @Test
    void codeforcesEmptyResultIsARealCompleteZero() {
        SubmissionPage page = codeforces("codeforces/status-empty.json");
        assertThat(page.submissions()).isEmpty();
        assertThat(page.reachedHistoryStart()).isTrue();
    }

    @Test
    void codeforcesUnknownHandleAndChangedFieldsAreErrorsNotZero() {
        fails(() -> codeforces("codeforces/status-notfound.json"), ErrorKind.NOT_FOUND, "not found");
        fails(() -> codeforces("codeforces/status-field-changed.json"), ErrorKind.PARSE_ERROR, "缺少编号、时间");
        fails(() -> codeforces("common/cloudflare-challenge.html"), ErrorKind.RATE_LIMITED, "防护验证页");
    }

    // ---- 比赛与 Rating ----

    private java.util.List<com.szpu.algohub.platform.PlatformDtos.RatedContest> cfRating(String file) {
        when(http.get(eq(Platform.CODEFORCES), contains("user.rating"))).thenReturn(sample(file));
        return new CodeforcesAdapter(http).fetchContestHistory(context("sample_user", "1", null));
    }

    private java.util.List<com.szpu.algohub.platform.PlatformDtos.RatedContest> atcoderHistory(String file) {
        when(http.get(eq(Platform.ATCODER), contains("/history/json"))).thenReturn(sample(file));
        return new AtCoderAdapter(http).fetchContestHistory(context("sample_user", "sample_user", null));
    }

    @Test
    void codeforcesRatingHistoryNormalEmptyAndChangedFields() {
        var contests = cfRating("codeforces/rating-normal.json");
        assertThat(contests).hasSize(3);
        assertThat(contests.getFirst().contestKey()).isEqualTo("2162");
        assertThat(contests.getFirst().oldRating()).isZero();
        assertThat(contests.getFirst().newRating()).isEqualTo(501);
        assertThat(contests.getFirst().place()).isEqualTo(4102);
        assertThat(contests.getFirst().finishedAt()).isEqualTo(Instant.ofEpochSecond(1760719800));
        assertThat(contests).allSatisfy(c -> assertThat(c.rated()).isTrue());
        assertThat(contests.getLast().newRating()).isEqualTo(1380);

        // 没参加过比赛：真实的零场，不是取不到
        assertThat(cfRating("codeforces/rating-empty.json")).isEmpty();
        fails(() -> cfRating("codeforces/rating-field-changed.json"), ErrorKind.PARSE_ERROR, "缺少比赛、时间或分数");
    }

    @Test
    void atcoderContestHistoryKeepsUnratedContestsAndRejectsChangedFields() {
        var contests = atcoderHistory("atcoder/history-normal.json");
        assertThat(contests).hasSize(3);
        assertThat(contests.getFirst().contestKey()).isEqualTo("abc441");
        assertThat(contests.getFirst().performance()).isEqualTo(775);
        assertThat(contests.getFirst().finishedAt()).isEqualTo(Instant.parse("2026-01-17T13:40:00Z"));
        // 不计分的一场：参加了、Rating 不变，表现分 0 不当真
        assertThat(contests.get(1).rated()).isFalse();
        assertThat(contests.get(1).performance()).isNull();
        assertThat(contests.get(1).newRating()).isEqualTo(contests.get(1).oldRating());
        // 表现分可以是负数
        assertThat(contests.getLast().performance()).isEqualTo(-96);

        assertThat(atcoderHistory("atcoder/history-empty.json")).isEmpty();
        fails(() -> atcoderHistory("atcoder/history-field-changed.json"), ErrorKind.PARSE_ERROR, "缺少比赛、时间或分数");
        fails(() -> atcoderHistory("common/cloudflare-challenge.html"), ErrorKind.RATE_LIMITED, "防护验证页");
    }

    // ---- AtCoder（kenkoooo） ----

    private SubmissionPage atcoder(String file) {
        when(http.get(eq(Platform.ATCODER), endsWith("/problems.json"))).thenReturn("[]");
        when(http.get(eq(Platform.ATCODER), endsWith("/problem-models.json"))).thenReturn("{}");
        when(http.get(eq(Platform.ATCODER), contains("user/submissions"))).thenReturn(sample(file));
        return new AtCoderAdapter(http).fetchSubmissions(context("sample_user", "sample_user", null));
    }

    @Test
    void atcoderNormalEmptyAndChangedFields() {
        SubmissionPage normal = atcoder("atcoder/submissions-normal.json");
        assertThat(normal.submissions()).hasSize(4);
        assertThat(normal.submissions()).allSatisfy(s -> assertThat(s.submittedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        assertThat(normal.reachedHistoryStart()).isTrue();

        SubmissionPage empty = atcoder("atcoder/submissions-empty.json");
        assertThat(empty.submissions()).isEmpty();
        assertThat(empty.reachedHistoryStart()).isTrue();

        fails(() -> atcoder("atcoder/submissions-field-changed.json"), ErrorKind.PARSE_ERROR, "缺少编号、时间");
    }

    // ---- 力扣 ----

    private TotalsSnapshot leetcode(String file) {
        when(http.postJson(eq(Platform.LEETCODE_CN), anyString(), anyString(), anyMap(), any())).thenReturn(sample(file));
        return new LeetCodeCnAdapter(http).fetchTotals(context("sample-user", "sample-user", null));
    }

    @Test
    void leetcodeDistinguishesTotalsTrueZeroUnknownUserAndChangedFields() {
        TotalsSnapshot normal = leetcode("leetcode/progress-normal.json");
        assertThat(normal.solvedTotal()).isEqualTo(42);
        assertThat(normal.difficultyBreakdown()).containsEntry("EASY", 12).containsEntry("MEDIUM", 24).containsEntry("HARD", 6);

        assertThat(leetcode("leetcode/progress-zero.json").solvedTotal()).isZero();
        // 实测：不存在的用户返回空列表——不是 0 题
        fails(() -> leetcode("leetcode/progress-notfound.json"), ErrorKind.NOT_FOUND, "不按 0 题处理");
        assertThatThrownBy(() -> leetcode("leetcode/progress-field-changed.json"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.PARSE_ERROR));
        fails(() -> leetcode("common/cloudflare-challenge.html"), ErrorKind.RATE_LIMITED, "防护验证页");
    }

    private SubmissionPage leetcodeRecent(String file) {
        when(http.postJson(eq(Platform.LEETCODE_CN), contains("noj-go"), anyString(), anyMap(), any())).thenReturn(sample(file));
        return new LeetCodeCnAdapter(http).fetchSubmissions(new SyncContext(1L, "sample-user", "sample-user", "700000000", null, null));
    }

    @Test
    void leetcodeRecentAcceptedGivesDetailsButHistoryNeverCompletes() {
        SubmissionPage page = leetcodeRecent("leetcode/recent-ac-normal.json");
        assertThat(page.submissions()).hasSize(3);
        assertThat(page.submissions().getFirst().problem().problemKey()).isEqualTo("decode-ways");
        assertThat(page.submissions().getFirst().problem().title()).startsWith("91. ");
        assertThat(page.submissions().getFirst().problem().url()).isEqualTo("https://leetcode.cn/problems/decode-ways/");
        assertThat(page.submissions().getFirst().submittedAt()).isEqualTo(Instant.ofEpochSecond(1789319971));
        assertThat(page.submissions()).allSatisfy(s -> assertThat(s.accepted()).isTrue());
        // 只公开最近这部分：永远不算"历史完整"，也没有下一页
        assertThat(page.reachedHistoryStart()).isFalse();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo("748926592");

        // 实测：没有最近通过记录（或查无此人）是空列表；游标保持不动
        SubmissionPage empty = leetcodeRecent("leetcode/recent-ac-empty.json");
        assertThat(empty.submissions()).isEmpty();
        assertThat(empty.nextCursor()).isEqualTo("700000000");
        fails(() -> leetcodeRecent("leetcode/recent-ac-field-changed.json"), ErrorKind.PARSE_ERROR, "缺少编号、时间");
        fails(() -> leetcodeRecent("common/cloudflare-challenge.html"), ErrorKind.RATE_LIMITED, "防护验证页");
    }

    // ---- 洛谷 ----

    @Test
    void luoguPracticePageNormalZeroMismatchAndChallenge() {
        TotalsSnapshot normal = LuoguPracticeParser.parse(sample("luogu/practice-normal.json"), "100001");
        assertThat(normal.solvedTotal()).isEqualTo(5);
        assertThat(normal.solvedProblems()).extracting(p -> p.problemKey()).contains("P1001");
        String summaryLagging = sample("luogu/practice-normal.json").replace("\"passedProblemCount\": 5", "\"passedProblemCount\": 4");
        assertThat(summaryLagging).isNotEqualTo(sample("luogu/practice-normal.json"));
        assertThat(LuoguPracticeParser.parse(summaryLagging, "100001").solvedTotal()).isEqualTo(5);

        assertThat(LuoguPracticeParser.parse(sample("luogu/practice-zero.json"), "100001").solvedTotal()).isZero();
        // 资料汇总大于逐题题单说明题单可能缺失：宁可不更新，也不写一个偏小的数。
        fails(() -> LuoguPracticeParser.parse(sample("luogu/practice-count-mismatch.json"), "100001"),
                ErrorKind.PARSE_ERROR, "不一致");
        // 返回的是别人的主页：不收
        fails(() -> LuoguPracticeParser.parse(sample("luogu/practice-normal.json"), "999"), ErrorKind.PARSE_ERROR, "UID");
        fails(() -> LuoguPracticeParser.parse(sample("common/cloudflare-challenge.html"), "100001"),
                ErrorKind.RATE_LIMITED, "防护");
    }

    // ---- 牛客 ----

    @Test
    void nowcoderNormalPageAdvancesToTheNextPage() {
        SubmissionPage page = NowcoderAdapter.parseSubmissions(sample("nowcoder/practice-normal.html"), 1, "100000001");
        assertThat(page.submissions()).hasSize(3);
        assertThat(page.nextCursor()).isEqualTo("2");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.reachedHistoryStart()).isFalse();
    }

    @Test
    void nowcoderRepeatsTheLastPageForOutOfRangePagesAndThatIsRejected() {
        // 实测：请求第 999 页，牛客原样返回最后一页的内容；当成新数据会重复计数或原地打转
        fails(() -> NowcoderAdapter.parseSubmissions(sample("nowcoder/practice-beyond-last-page.html"), 999, "100000001"),
                ErrorKind.PARSE_ERROR, "分页未推进");
    }

    @Test
    void nowcoderZeroChangedColumnsWrongUserAndChallenge() {
        SubmissionPage zero = NowcoderAdapter.parseSubmissions(sample("nowcoder/practice-zero.html"), 1, "100000001");
        assertThat(zero.submissions()).isEmpty();
        assertThat(zero.reachedHistoryStart()).isTrue();
        when(http.get(eq(Platform.NOWCODER), contains("/practice-coding"), anyMap(), any()))
                .thenReturn(sample("nowcoder/practice-zero.html"));
        assertThat(new NowcoderAdapter(http).fetchTotals(context("100000001", "100000001", null)).solvedTotal()).isZero();

        fails(() -> NowcoderAdapter.parseSubmissions(sample("nowcoder/practice-field-changed.html"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(sample("nowcoder/practice-normal.html"), 1, "5"),
                ErrorKind.PARSE_ERROR, "身份");
        fails(() -> NowcoderAdapter.parseSubmissions(sample("common/cloudflare-challenge.html"), 1, "100000001"),
                ErrorKind.RATE_LIMITED, "防护验证页");
    }

    @Test
    void nowcoderStillAcceptsAnEmptyTableWithoutAPlaceholder() {
        String html = sample("nowcoder/practice-zero.html").replaceAll("(?s)<tbody>.*?</tbody>", "<tbody></tbody>");
        SubmissionPage page = NowcoderAdapter.parseSubmissions(html, 1, "100000001");
        assertThat(page.submissions()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.reachedHistoryStart()).isTrue();
    }

    @Test
    void nowcoderPlaceholderRequiresAnExplicitZeroCountAndNoNextPage() {
        String html = sample("nowcoder/practice-zero.html");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("<div class=\"state-num\">0</div>",
                        "<div class=\"state-num\">1</div>"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("次提交", "未知统计"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("</body>",
                        "<li class=\"js-next-pager\"><a data-page=\"2\">下一页</a></li></body>"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
    }

    @Test
    void nowcoderDoesNotTreatOtherMessagesOrMixedRowsAsEmptyHistory() {
        String html = sample("nowcoder/practice-zero.html");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("没有找到你想要的内容呢", "请登录后查看"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("colspan=\"1024\"", ""), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("</tbody>", "<tr><td>未知记录</td></tr></tbody>"), 1, "100000001"),
                ErrorKind.PARSE_ERROR, "字段变化");
        fails(() -> NowcoderAdapter.parseSubmissions(html.replace("</body>", "<div>安全验证</div></body>"), 1, "100000001"),
                ErrorKind.RATE_LIMITED, "防护验证页");
    }

    /** 归属验证读公开主页头像旁的个人简介；简介为空、用户不存在、页面改版要分别处理 */
    @Test
    void nowcoderProfileExposesTheBriefUsedForOwnershipProof() {
        var identity = NowcoderAdapter.parseIdentity("100000001", sample("nowcoder/profile-normal.html"));
        assertThat(identity.platformUid()).isEqualTo("100000001");
        assertThat(identity.displayName()).isEqualTo("sample_user");
        assertThat(identity.extra()).containsEntry("brief", "刷题中 AlgoHub-SAMPLE-7F3K2Q");

        // 没写简介：读到了、但是空的——核验时提示「还没有验证串」，不是「暂时读不到」
        assertThat(NowcoderAdapter.parseIdentity("100000001", sample("nowcoder/profile-empty-brief.html")).extra())
                .containsEntry("brief", "");
        // 简介区改版读不到：不放 brief，核验时按「暂时读不到」处理、不扣次数
        assertThat(NowcoderAdapter.parseIdentity("100000001", sample("nowcoder/profile-field-changed.html")).extra())
                .doesNotContainKey("brief");
        // 实测：不存在的 UID 返回「牛客网-用户不存在」页，原来会把这几个字当成昵称
        fails(() -> NowcoderAdapter.parseIdentity("1", sample("nowcoder/profile-notfound.html")),
                ErrorKind.NOT_FOUND, "未找到");

        var proof = new NowcoderAdapter(http).ownershipProof();
        assertThat(proof).isNotNull();
        assertThat(proof.field()).isEqualTo("brief");
    }

    @Test
    void nowcoderRatingHistoryNormalEmptyAndChangedFields() {
        var contests = NowcoderAdapter.parseRatingHistory(sample("nowcoder/rating-normal.json"));
        assertThat(contests).hasSize(3);
        var first = contests.getFirst();
        assertThat(first.contestKey()).isEqualTo("110696");
        assertThat(first.contestName()).isEqualTo("牛客周赛 Round 94");
        assertThat(first.place()).isEqualTo(592);
        assertThat(first.oldRating()).isEqualTo(1000);
        assertThat(first.newRating()).isEqualTo(1011);
        assertThat(first.rated()).isTrue();
        assertThat(first.finishedAt()).isEqualTo(Instant.ofEpochMilli(1748170800000L));
        assertThat(contests.get(1).oldRating()).isEqualTo(1011);
        assertThat(contests.get(1).newRating()).isEqualTo(989);

        assertThat(NowcoderAdapter.parseRatingHistory(sample("nowcoder/rating-empty.json"))).isEmpty();
        fails(() -> NowcoderAdapter.parseRatingHistory(sample("nowcoder/rating-field-changed.json")),
                ErrorKind.PARSE_ERROR, "缺少");
        fails(() -> NowcoderAdapter.parseRatingHistory(sample("common/cloudflare-challenge.html")),
                ErrorKind.RATE_LIMITED, "防护");
    }

    // ---- QOJ ----

    @Test
    void qojLoginPageMeansCredentialInvalidAndIsNotMistakenForAChallenge() {
        String login = sample("qoj/login-page.html");
        // 正常页面也带 Cloudflare 的检测脚本（/cdn-cgi/challenge-platform/scripts/jsd/），不能据此当成防护页
        assertThat(login).contains("challenge-platform/scripts/jsd");
        assertThat(ProtectionPage.looksLike(login)).isFalse();

        when(http.get(eq(Platform.QOJ), anyString(), anyMap(), any())).thenReturn(login);
        fails(() -> new QojAdapter(http).fetchTotals(context("sample_user", "sample_user", "UOJSESSID=x")),
                ErrorKind.CREDENTIAL_INVALID, "登录");
        fails(() -> QojRecordParser.parse(login, "sample_user", 1), ErrorKind.CREDENTIAL_INVALID, "登录");
    }

    @Test
    void qojChallengePageIsRetriedNotParsedAsZero() {
        String challenge = sample("common/cloudflare-challenge.html");
        assertThat(ProtectionPage.looksLike(challenge)).isTrue();
        when(http.get(eq(Platform.QOJ), anyString(), anyMap(), any())).thenReturn(challenge);
        fails(() -> new QojAdapter(http).fetchTotals(context("sample_user", "sample_user", "UOJSESSID=x")),
                ErrorKind.RATE_LIMITED, "防护验证页");
        fails(() -> QojRecordParser.parse(challenge, "sample_user", 1), ErrorKind.RATE_LIMITED, "防护验证页");
    }

    // ---- 码蹄集 ----

    private TotalsSnapshot matiji(String file) {
        when(http.postForm(eq(Platform.MATIJI), anyString(), anyMap(), anyMap(), any())).thenReturn(sample(file));
        return new MatijiAdapter(http).fetchTotals(context("10001", "10001", null));
    }

    @Test
    void matijiNormalUnknownUserChangedFieldAndChallenge() {
        assertThat(matiji("matiji/user-normal.json").solvedTotal()).isEqualTo(1678);
        fails(() -> matiji("matiji/user-notfound.json"), ErrorKind.NOT_FOUND, "未找到");
        fails(() -> matiji("matiji/user-field-changed.json"), ErrorKind.PARSE_ERROR, "结构");
        fails(() -> matiji("common/cloudflare-challenge.html"), ErrorKind.RATE_LIMITED, "防护验证页");
    }
}
