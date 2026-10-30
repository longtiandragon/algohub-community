package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.CredentialPayload;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 蓝桥云课适配器：样例按 2026-09-28 实测的接口结构手写（不是真实同学的记录）。
 * 公开资料带职位（归属验证）；提交记录要登录、只返回登录者自己的、偏移分页、倒序、没有提交编号。
 */
class LanqiaoAdapterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String USER = """
            {"id":1234567,"name":"示例同学","job_title":"AlgoHub-abcdefghijklmnop","join_date":"2024-09-24","level":12}
            """;
    private static final String RANK_ME = """
            {"data":[{"rank":1,"id":999,"count":2000,"name":"榜首"}],
             "user_info":{"id":1234567,"name":"示例同学","count":10,"rank":6000}}
            """;
    private static final String RANK_OTHER = """
            {"data":[],"user_info":{"id":7654321,"name":"别人","count":3}}
            """;
    private static final String RANK_LOGGED_OUT = """
            {"data":[{"rank":1,"id":999,"count":2000,"name":"榜首"}]}
            """;
    private static final String PAGE_1 = """
            {"next":"https://www.lanqiao.cn/api/v2/problems/submissions/?cursor=bz0xMDA%3D&page_size=100","previous":null,
             "results":[
              {"problem_name":"最新一题","is_passed":true,"created_at":"2026-06-05T13:38:00.762000+08:00","problem_id":20162,"contest_id":null,"language":"cpp"},
              {"problem_name":"没过的题","is_passed":false,"created_at":"2026-06-04T23:55:36.100000+08:00","problem_id":20100,"contest_id":null,"language":"python3"}
             ]}
            """;
    private static final String PAGE_2 = """
            {"next":null,"previous":"x",
             "results":[
              {"problem_name":"最早一题","is_passed":true,"created_at":"2024-09-24T13:15:25.673000+08:00","problem_id":145,"contest_id":null,"language":"c"}
             ]}
            """;

    private final PlatformHttpClient http = mock(PlatformHttpClient.class);
    private final LanqiaoAdapter adapter = new LanqiaoAdapter(http);
    private final String credential = LanqiaoAdapter.credentialOf(TOKEN);

    private void respond(String urlPart, String body) {
        when(http.get(eq(Platform.LANQIAO), org.mockito.ArgumentMatchers.contains(urlPart), anyMap(), anyString())).thenReturn(body);
    }

    @Test
    void publicProfileGivesTheNumericIdAndTheJobTitleForOwnershipProof() {
        when(http.get(eq(Platform.LANQIAO), eq("https://www.lanqiao.cn/api/v2/users/1234567/"), anyMap(), isNull())).thenReturn(USER);
        var identity = adapter.resolveIdentity("https://www.lanqiao.cn/users/1234567/", null);
        assertThat(identity.platformUid()).isEqualTo("1234567");
        assertThat(identity.displayName()).isEqualTo("示例同学");
        assertThat(identity.extra()).containsEntry("jobTitle", "AlgoHub-abcdefghijklmnop");
        var proof = adapter.ownershipProof();
        assertThat(proof.field()).isEqualTo("jobTitle");
        assertThat(proof.location()).contains("职位");
        assertThat(proof.settingsUrl()).isEqualTo("https://passport.lanqiao.cn/main?action=userinfo&usertype=-1");
        assertThat(adapter.lookupKey("1234567", "示例同学")).isEqualTo("1234567");
    }

    @Test
    void userIdComesFromTheNumberOrTheProfileLinkOnly() {
        assertThat(LanqiaoAdapter.extractUserId(" 3057126 ")).isEqualTo("3057126");
        assertThat(LanqiaoAdapter.extractUserId("https://www.lanqiao.cn/users/3057126/")).isEqualTo("3057126");
        assertThatThrownBy(() -> LanqiaoAdapter.extractUserId("Wa学弟")).isInstanceOf(ApiException.class);
    }

    @Test
    void onlyTheLqtokenIsKeptFromAPastedCookie() {
        String full = "Cookie: sensorsdata=abc; lqtoken=" + TOKEN + "; _SESSIONKEY=xyz; platform=LANQIAO-FE";
        assertThat(CredentialPayload.parse(LanqiaoAdapter.credentialOf(full)).cookie()).isEqualTo("lqtoken=" + TOKEN);
        assertThat(CredentialPayload.parse(LanqiaoAdapter.credentialOf("lqtoken=" + TOKEN)).cookie()).isEqualTo("lqtoken=" + TOKEN);
        assertThat(CredentialPayload.parse(LanqiaoAdapter.credentialOf(TOKEN)).cookie()).isEqualTo("lqtoken=" + TOKEN);
        assertThatThrownBy(() -> LanqiaoAdapter.credentialOf("_SESSIONKEY=xyz")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LanqiaoAdapter.credentialOf(null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getKind()).isEqualTo(ErrorKind.CREDENTIAL_INVALID));
    }

    @Test
    void submissionsWithoutAnIdGetProblemPlusTimeAsTheirKey() throws Exception {
        var node = new ObjectMapper().readTree(PAGE_1).path("results").get(0);
        var submission = LanqiaoAdapter.toSubmission(node);
        assertThat(submission.accepted()).isTrue();
        assertThat(submission.verdict()).isEqualTo("AC");
        assertThat(submission.submittedAt()).isEqualTo(Instant.parse("2026-06-05T05:38:00.762Z"));
        assertThat(submission.platformSubId()).isEqualTo("20162-" + Instant.parse("2026-06-05T05:38:00.762Z").toEpochMilli());
        assertThat(submission.problem().problemKey()).isEqualTo("20162");
        assertThat(submission.problem().url()).isEqualTo("https://www.lanqiao.cn/problems/20162/learning/");
        assertThat(LanqiaoAdapter.offsetCursor(16)).isEqualTo("bz0xNg==");
        assertThat(LanqiaoAdapter.offsetCursor(100)).isEqualTo("bz0xMDA=");
    }

    @Test
    void firstSyncReadsEveryPageThenIncrementalSyncStopsAtKnownRecords() {
        respond("pass-count-rank", RANK_ME);
        respond("submissions/?page_size=100&cursor=", PAGE_2);
        when(http.get(eq(Platform.LANQIAO), eq("https://www.lanqiao.cn/api/v2/problems/submissions/?page_size=100"), anyMap(), anyString()))
                .thenReturn(PAGE_1);

        var first = adapter.fetchSubmissions(new SyncContext(1L, "示例同学", "1234567", null, credential, null));
        assertThat(first.submissions()).hasSize(3);
        assertThat(first.hasMore()).isFalse();
        assertThat(first.reachedHistoryStart()).isTrue();
        long newest = Instant.parse("2026-06-05T05:38:00.762Z").toEpochMilli();
        assertThat(first.nextCursor()).isEqualTo("latest=" + newest);

        // 没有新提交：第一条就是已知的，读一页就停，不再往前翻
        var again = adapter.fetchSubmissions(new SyncContext(1L, "示例同学", "1234567", first.nextCursor(), credential, null));
        assertThat(again.submissions()).isEmpty();
        assertThat(again.nextCursor()).isEqualTo("latest=" + newest);

        // 回查窗口内的已知记录会重新读（处理迟到的数据），由编号去重
        var recheck = adapter.fetchSubmissions(new SyncContext(1L, "示例同学", "1234567", first.nextCursor(), credential,
                Instant.parse("2026-06-01T00:00:00Z")));
        assertThat(recheck.submissions()).hasSize(2);
    }

    @Test
    void aSessionOfAnotherAccountOrALoggedOutSessionIsRejectedBeforeReadingRecords() {
        respond("pass-count-rank", RANK_OTHER);
        assertThatThrownBy(() -> adapter.fetchSubmissions(new SyncContext(1L, "示例同学", "1234567", null, credential, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("7654321")
                .satisfies(e -> assertThat(((ApiException) e).getKind()).isEqualTo(ErrorKind.CREDENTIAL_INVALID));
    }

    @Test
    void aLoggedOutSessionAsksForANewToken() {
        respond("pass-count-rank", RANK_LOGGED_OUT);
        assertThatThrownBy(() -> adapter.fetchSubmissions(new SyncContext(1L, "示例同学", "1234567", null, credential, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getKind()).isEqualTo(ErrorKind.CREDENTIAL_INVALID));
    }

    @Test
    void cursorKeepsTheUnfinishedRoundAndToleratesGarbage() {
        var c = LanqiaoAdapter.Cursor.parse("latest=5;off=2000;pending=9");
        assertThat(c).isEqualTo(new LanqiaoAdapter.Cursor(5, 2000, 9));
        assertThat(c.format()).isEqualTo("latest=5;off=2000;pending=9");
        assertThat(LanqiaoAdapter.Cursor.parse("latest=abc;off=-3").format()).isEqualTo("latest=0");
        assertThat(LanqiaoAdapter.Cursor.parse(null).format()).isEqualTo("latest=0");
    }
}
