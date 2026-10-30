package com.szpu.algohub.platform.adapter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NowcoderHistoryTest {
    @Test void trackerRequiresMatchingUidAndDoesNotTreatMissingAsZero() {
        String json="{\"code\":0,\"data\":{\"ranks\":[{\"uid\":337279670,\"count\":114}]}}";
        assertThat(NowcoderAdapter.trackerSummary(json,"337279670")).contains("114");
        assertThatThrownBy(() -> NowcoderAdapter.trackerSummary(json,"999")).isInstanceOf(RuntimeException.class);
    }
    private static final String HTML = """
        <div class="my-state-item">150</div><table><tbody><tr>
        <td><a href="/acm/contest/view-submission?submissionId=123&uid=337279670">123</a></td>
        <td><a href="/acm/problem/319688">题目</a></td><td>答案正确</td><td>100</td>
        <td>1</td><td>2</td><td>3</td><td>C++</td><td>2026-08-22 01:27:49</td>
        </tr></tbody></table><li class="js-next-pager"><a data-page="2">下一页</a></li>
        """;
    @Test void readsRealTimeAndNextPageWithoutDeclaringFullHistory() {
        var page = NowcoderAdapter.parseSubmissions(HTML,1,"337279670");
        assertThat(page.submissions()).hasSize(1);
        assertThat(page.submissions().getFirst().submittedAt()).isEqualTo(java.time.Instant.parse("2026-08-21T17:27:49Z"));
        assertThat(page.submissions().getFirst().accepted()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("2");
        assertThat(page.reachedHistoryStart()).isFalse();
        assertThat(NowcoderAdapter.parseSubmissions(HTML.replace("js-next-pager","js-next-pager disabled"),1,"337279670").reachedHistoryStart()).isTrue();
    }
    @Test void rejectsChallengeWrongIdentityAndStalledPagination() {
        assertThatThrownBy(() -> NowcoderAdapter.parseSubmissions("<html>login</html>",1,"337279670")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NowcoderAdapter.parseSubmissions(HTML,1,"999")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NowcoderAdapter.parseSubmissions(HTML,2,"337279670")).isInstanceOf(RuntimeException.class);
    }
}
