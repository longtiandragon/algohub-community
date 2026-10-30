package com.szpu.algohub.platform.adapter;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class QojRecordParserTest {
    @Test void profileHeadingSeparatesAcceptedFromTriedProblems() {
        var http=org.mockito.Mockito.mock(com.szpu.algohub.platform.PlatformHttpClient.class);
        org.mockito.Mockito.when(http.get(org.mockito.ArgumentMatchers.eq(com.szpu.algohub.platform.Platform.QOJ),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.any()))
                .thenReturn("<title>sample_user</title><h4 class='list-group-item-heading'>Accepted problems：2 problems</h4><p class='list-group-item-text'><a href='/problem/1'>1</a><a href='/problem/2'>2</a></p><h4 class='list-group-item-heading'>Tried problems：1 problem</h4><p class='list-group-item-text'><a href='/problem/3'>3</a></p>");
        assertThat(new QojAdapter(http).fetchTotals(new com.szpu.algohub.platform.PlatformDtos.SyncContext(9L,"sample_user","sample_user",null,"test",null)).solvedTotal()).isEqualTo(2);
    }
    String fixture(int page) throws Exception {
        try(var in=getClass().getResourceAsStream("/qoj/ac-page"+page+".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void realAccountPagesKeepTwentyAcRecordsForNineteenProblems() throws Exception {
        var first=QojRecordParser.parse(fixture(1),"sample_user",1);
        var last=QojRecordParser.parse(fixture(2),"sample_user",2);
        assertThat(first.nextCursor()).isEqualTo("2");
        assertThat(first.reachedHistoryStart()).isFalse();
        assertThat(last.reachedHistoryStart()).isTrue();
        var rows=Stream.concat(first.submissions().stream(),last.submissions().stream()).toList();
        assertThat(rows).hasSize(20);
        assertThat(rows.stream().map(r->r.problem().problemKey()).distinct().count()).isEqualTo(19);
        assertThat(rows.stream().filter(r->r.problem().problemKey().equals("20245")).map(r->r.submittedAt()).min(Instant::compareTo).orElseThrow())
                .isEqualTo(Instant.parse("2026-09-12T19:23:20Z"));
    }
    @Test void rejectsOtherUserWrongScoreMissingTimeAndLogin() throws Exception {
        String html=fixture(1);
        for(String bad:java.util.List.of(html.replace("sample_user","other"),html.replace("data-score=\"100.000000\"","data-score=\"10.000000\""),
                html.replace("2026-09-13 03:25:18","unknown"),"<title>Login</title><input type=password>")) {
            assertThatThrownBy(()->QojRecordParser.parse(bad,"sample_user",1)).isInstanceOf(com.szpu.algohub.common.ApiException.class);
        }
    }
}
