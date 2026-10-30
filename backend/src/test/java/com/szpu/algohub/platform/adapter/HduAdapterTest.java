package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformHttpClient;

class HduAdapterTest {
    private static final String HANDLE = "sample_user_hdu";
    private static final String URL = "https://acm.hdu.edu.cn/status.php?user=" + HANDLE;

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/hdu-status.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SyncContext context(String cursor, Instant recheck) {
        return new SyncContext(1L, HANDLE, HANDLE, cursor, null, recheck);
    }

    @Test
    void realFixtureIncludesFailuresAndConvertsBeijingTime() throws Exception {
        var page = HduAdapter.parseStatus(fixture(), HANDLE);
        assertThat(page.rows()).hasSize(4);
        assertThat(page.rows().stream().filter(s -> s.accepted())).hasSize(1);
        var ac = page.rows().get(1);
        assertThat(ac.platformSubId()).isEqualTo("40936621");
        assertThat(ac.problem().problemKey()).isEqualTo("3949");
        assertThat(ac.submittedAt()).isEqualTo(Instant.parse("2026-07-23T14:37:56Z"));
        assertThat(ac.problem().difficultyRaw()).isNull();
        assertThat(page.hasNext()).isFalse(); // Last Page is not Next Page.
    }

    @Test
    void changedPageAndWrongUserAreErrorsNotZeroHistory() throws Exception {
        assertThatThrownBy(() -> HduAdapter.parseStatus("<html>challenge</html>", HANDLE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.PARSE_ERROR));
        String html = fixture();
        assertThatThrownBy(() -> HduAdapter.parseStatus(html, "another-user"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HduAdapter.parseStatus(html.replace("2026-07-23 22:37:56", "not-a-date"), HANDLE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void continuationUsesInclusiveBoundaryMinusOneAndCanFinishEmpty() throws Exception {
        var http = mock(PlatformHttpClient.class);
        String html = fixture();
        String next = html + "<a href='/status.php?first=40936617'>Next Page &gt;</a>";
        when(http.get(eq(Platform.HDU), anyString(), eq(Map.of()), isNull(), any(Charset.class)))
                .thenReturn(next, html.replaceAll("(?s)<tr[^>]*><td height=22px>.*?</tr>", ""));
        var adapter = new HduAdapter(http);
        var first = adapter.fetchSubmissions(context(null, null));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.reachedHistoryStart()).isFalse();
        assertThat(first.nextCursor()).isEqualTo("scan:0:40941008:40936617");
        var last = adapter.fetchSubmissions(context(first.nextCursor(), null));
        assertThat(last.submissions()).isEmpty();
        assertThat(last.hasMore()).isFalse();
        assertThat(last.reachedHistoryStart()).isTrue();
        assertThat(last.nextCursor()).isEqualTo("40941008");
        verify(http).get(eq(Platform.HDU), eq(URL + "&first=40936617"), eq(Map.of()), isNull(), any(Charset.class));
    }

    @Test
    void incrementalReadsNewSubmissionsAndRechecksKnownWindow() throws Exception {
        var http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.HDU), anyString(), eq(Map.of()), isNull(), any(Charset.class)))
                .thenReturn(fixture());
        var adapter = new HduAdapter(http);
        var page = adapter.fetchSubmissions(context("40936621", Instant.parse("2026-07-24T00:00:00Z")));
        assertThat(page.submissions()).hasSize(1);
        assertThat(page.nextCursor()).isEqualTo("40941008");
        assertThat(page.reachedHistoryStart()).isFalse();
        var recheck = adapter.fetchSubmissions(context("40941008", Instant.parse("2026-07-01T00:00:00Z")));
        assertThat(recheck.submissions()).hasSize(4);
    }

    @Test
    void serverIgnoringPageBoundaryFailsWithoutAdvancingCursor() throws Exception {
        var http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.HDU), anyString(), eq(Map.of()), isNull(), any(Charset.class)))
                .thenReturn(fixture());
        assertThatThrownBy(() -> new HduAdapter(http).fetchSubmissions(context("scan:0:40941008:40936617", null)))
                .isInstanceOf(ApiException.class);
    }
}
