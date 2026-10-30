package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QojServerCollectionTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode page() throws Exception {
        return (ObjectNode) json.readTree("""
                {"current":"1","empty":false,"sourceClock":"Server Time: 2026-01-03 12:00:00 (UTC+8)",
                "rows":[{"id":"/submission/101","problem":"/contest/20/problem/7","title":"Example",
                "submitter":"sample_user","result":"AC ✓","score":"100.000000","full":"100.000000",
                "time":"2026-01-02 00:30:00","language":"C++17"}],
                "pagination":["/submissions?submitter=sample_user&page=2"]}
                """);
    }

    @Test void sourceTimezoneAndExplicitAcAndPaginationArePreserved() throws Exception {
        var first = QojRecordParser.parseCollected(page(), "sample_user", 1);
        assertThat(first.submissions().getFirst().submittedAt()).isEqualTo(Instant.parse("2026-01-01T16:30:00Z"));
        assertThat(first.submissions().getFirst().accepted()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("2");
        assertThat(first.reachedHistoryStart()).isFalse();
        var last = page();
        last.putArray("pagination");
        var end = QojRecordParser.parseCollected(last, "sample_user", 1);
        assertThat(end.hasMore()).isFalse();
        assertThat(end.reachedHistoryStart()).isFalse();
    }

    @Test void fullScoreWithoutAcLabelIsNotAccepted() throws Exception {
        var data = page();
        ((ObjectNode) data.get("rows").get(0)).put("result", "100");
        assertThat(QojRecordParser.parseCollected(data,"sample_user",1).submissions().getFirst().accepted()).isFalse();
    }

    @Test void clampedPageAndMismatchedLinksAreNotSilentlyImported() throws Exception {
        var data = page();
        assertThatThrownBy(() -> QojRecordParser.parseCollected(data,"sample_user",6)).isInstanceOf(ApiException.class);
        data.putArray("pagination").add("/submissions?submitter=other_user&page=2");
        assertThatThrownBy(() -> QojRecordParser.parseCollected(data,"sample_user",1)).isInstanceOf(ApiException.class);
    }

    @Test void unknownTimeAndDuplicateIdsAndWrongSubmitterRejectEntirePage() throws Exception {
        for (String field : new String[]{"time","submitter","id"}) {
            var data = page();
            ((ObjectNode) data.get("rows").get(0)).put(field, "unknown");
            assertThatThrownBy(() -> QojRecordParser.parseCollected(data,"sample_user",1)).isInstanceOf(ApiException.class);
        }
        var data = page();
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.get("rows")).add(data.get("rows").get(0).deepCopy());
        assertThatThrownBy(() -> QojRecordParser.parseCollected(data,"sample_user",1)).isInstanceOf(ApiException.class);
    }

    @Test void genuineEmptyFirstPageAcceptedButLaterEmptyPageRejected() throws Exception {
        var data = page();
        data.putArray("rows"); data.putArray("pagination"); data.putNull("current"); data.put("empty",true);
        assertThat(QojRecordParser.parseCollected(data,"sample_user",1).submissions()).isEmpty();
        assertThatThrownBy(() -> QojRecordParser.parseCollected(data,"sample_user",2)).isInstanceOf(ApiException.class);
    }

    @Test void serverAdapterUsesMottoAndSnapshotWithoutUserCredentialOrFakeRecords() throws Exception {
        var server = mock(QojServerClient.class);
        when(server.enabled()).thenReturn(true);
        when(server.profile("sample_user")).thenReturn(json.readTree("""
                {"motto":"example-verification","total":2,"problemIds":["1","2"]}
                """));
        var adapter = new QojAdapter(null, server);
        assertThat(adapter.resolveIdentity("sample_user",null).extra()).containsEntry("motto","example-verification");
        assertThat(adapter.ownershipProof().field()).isEqualTo("motto");
        assertThat(adapter.capability().requiresCredential()).isFalse();
        var totals = adapter.fetchTotals(new SyncContext(1L,"sample_user","sample_user",null,null,null));
        assertThat(totals.solvedTotal()).isEqualTo(2);
        assertThat(totals.solvedProblems()).isEmpty();
        assertThat(totals.dailyActivity()).isEmpty();
    }

    @Test void platformSessionFailureIsNotAnIndividualCredentialFailure() {
        assertThat(QojServerClient.failure("SESSION_EXPIRED").getKind()).isEqualTo(ErrorKind.UPSTREAM_ERROR);
        assertThat(QojServerClient.failure("ACCESS_RESTRICTED").getKind()).isEqualTo(ErrorKind.FORBIDDEN);
        assertThat(QojServerClient.failure("RATE_LIMITED").getKind()).isEqualTo(ErrorKind.RATE_LIMITED);
        assertThat(QojServerClient.failure("NETWORK").getKind()).isEqualTo(ErrorKind.NETWORK);
        assertThat(QojServerClient.failure("PARSE_ERROR").getKind()).isEqualTo(ErrorKind.PARSE_ERROR);
    }
}
