package com.szpu.algohub.weekly;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.adapter.ContestCategoryRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeeklySourceTest {
    private final PlatformHttpClient http = mock(PlatformHttpClient.class);
    private final WeeklySource source = new WeeklySource(http,null);

    @ParameterizedTest
    @CsvSource({"Div. 1,Div.1","Div. 2,Div.2","Div. 1 + Div. 2,Div.1+Div.2",
            "Div 1+2,Div.1+Div.2","Div.3,Div.3","Div. 4,Div.4"})
    void recognizeDivisions(String title,String expected) {
        assertThat(ContestCategoryRules.classifyCodeforces("Codeforces Round (" + title + ")")).isEqualTo(expected);
    }

    @Test
    void rejectUserInfoForeignDomainsAndUnexpectedContestPathsBeforeAnyRequest() {
        for (String link : new String[]{"https://codeforces.com@example.org/contest/2278",
                "https://codeforces.com:8080/contest/2278","https://example.org/contest/2278",
                "https://codeforces.com/contest/%2e%2e","https://atcoder.jp/login","/contests/abc480"}) {
            assertThatThrownBy(() -> source.preview(link)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(http);
    }

    @Test
    void atcoderTaskLabelAndExternalProblemKeyAreIndependent() {
        when(http.get(Platform.ATCODER, "https://atcoder.jp/contests/abc001/tasks?lang=en")).thenReturn("""
                <a class="contest-title" href="/contests/abc001">AtCoder Beginner Contest 001</a>
                <table><tbody><tr><td><a href="/contests/abc001/tasks/abc001_1">A</a></td></tr></tbody></table>
                """);
        var meta = source.preview("https://atcoder.jp/contests/abc001/?lang=ja");
        assertThat(meta.category()).isEqualTo("ABC");
        assertThat(meta.tasks()).containsExactly(new WeeklySource.Task("A","abc001_1"));
        assertThat(source.results(meta,"student",null)).isEmpty();
    }

    @Test
    void nowcoderKeepsBoardOnlyPracticeWithUnknownAttempts() {
        String base = "https://ac.nowcoder.com";
        String url = base + "/acm/contest/123";
        when(http.get(Platform.NOWCODER,url)).thenReturn("<title>牛客小白月赛1_牛客竞赛OJ</title>");
        when(http.get(Platform.NOWCODER,base + "/acm/contest/problem-list?id=123"))
            .thenReturn("""
                {"code":0,"data":{"data":[{"index":"A","problemId":12}]}}
                """);
        when(http.get(Platform.NOWCODER,base + "/acm-heavy/acm/contest/real-time-rank-data?id=123&rankScope=ALL&page=1&pageSize=200&limit=100000"))
            .thenReturn("""
                {"code":0,"data":{"basicInfo":{"contestId":123,"pageCurrent":1,"pageCount":1,"rankType":"ICPC",
                "contestBeginTime":1000,"contestEndTime":2000},"rankData":[{"uid":9,"participationType":"STAR",
                "scoreList":[{"problemId":12,"submit":false,"postContestAccepted":true}]}]}}
                """);
        when(http.get(Platform.NOWCODER,base + "/acm/contest/status-list?id=123&searchUserName=9&page=1&pageSize=50"))
            .thenReturn("""
                {"code":0,"data":{"basicInfo":{"contestId":123,"pageCount":1},"data":[]}}
                """);
        var rows = source.results(source.preview(url),"9","9");
        assertThat(rows).extracting(WeeklySource.Row::kind).containsExactly("STAR","PRACTICE");
        assertThat(rows.getFirst().cells()).isEmpty();
        assertThat(rows.getLast().cells().get("A")).isEqualTo(new WeeklySource.Cell(true,null,null,null));
    }

    @Test
    void partialScoresAndRepeatVirtualStartsDoNotCombineIntoOnePerformance() {
        String standings = "https://codeforces.com/api/contest.standings?contestId=1";
        when(http.get(Platform.CODEFORCES, standings)).thenReturn("""
            {"status":"OK","result":{"contest":{"id":1,"name":"Round (Div. 2)","type":"IOI"},
             "problems":[{"index":"A"}],"rows":[{"party":{"members":[{"handle":"one"}],"participantType":"CONTESTANT"},
             "points":47,"penalty":0,"problemResults":[{"points":47,"rejectedAttemptCount":0,"bestSubmissionTimeSeconds":100}]}]}}
            """);
        when(http.get(Platform.CODEFORCES,"https://codeforces.com/api/contest.status?contestId=1&handle=one&from=1&count=200"))
            .thenReturn("""
            {"status":"OK","result":[
            {"id":4,"author":{"participantType":"VIRTUAL","startTimeSeconds":20,"members":[{"handle":"one"}]},
            "problem":{"index":"A"},"relativeTimeSeconds":50,"verdict":"OK"},
            {"id":3,"author":{"participantType":"VIRTUAL","startTimeSeconds":10,"members":[{"handle":"one"}]},
            "problem":{"index":"A"},"relativeTimeSeconds":10,"verdict":"WRONG_ANSWER"}]}
            """);
        var meta = source.preview("https://codeforces.com/contest/1");
        var rows = source.results(meta,"one",null);
        assertThat(rows.stream().filter(r -> r.kind().equals("VIRTUAL"))).hasSize(2);
        assertThat(rows.stream().filter(r -> r.kind().equals("OFFICIAL")).findFirst().orElseThrow().cells().get("A"))
                .isEqualTo(new WeeklySource.Cell(false,0,47d,100L));
        verify(http,times(1)).get(Platform.CODEFORCES,standings);
    }
}
