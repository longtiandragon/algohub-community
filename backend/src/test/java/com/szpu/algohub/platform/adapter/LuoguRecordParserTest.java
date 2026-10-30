package com.szpu.algohub.platform.adapter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;

class LuoguRecordParserTest {
    @Test void completedHistoryCanStopAtRecheckBoundaryWithoutClaimingHistoryStart() {
        String body = page(6, row(6,12,"100001",1789000100)+","+
                row(5,12,"100001",1789000000)+","+row(4,12,"100001",1788000000));
        var cutoff = Instant.ofEpochSecond(1788500000);
        var incremental = LuoguRecordParser.parse(body,"100001",1,cutoff);
        assertThat(incremental.hasMore()).isFalse();
        assertThat(incremental.reachedHistoryStart()).isFalse();
        assertThat(incremental.nextCursor()).isNull();
        assertThat(incremental.submissions()).hasSize(3);
        assertThat(LuoguRecordParser.parse(body,"100001",1).hasMore()).isTrue();
        assertThat(LuoguRecordParser.parse(body,"100001",1,Instant.ofEpochSecond(1788000000)).hasMore()).isTrue();
    }

    @Test void outOfOrderPageDoesNotStopEarly() {
        String body = page(6,row(6,12,"100001",1788000000)+","+
                row(5,12,"100001",1789000000)+","+row(4,12,"100001",1787000000));
        assertThat(LuoguRecordParser.parse(body,"100001",1,Instant.ofEpochSecond(1788500000)).hasMore()).isTrue();
    }
    @Test void rejectsShortAndOutOfRangePagesRatherThanCompletingHistory() {
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(3,row(1,12,"100001",1787000000)),"100001",1))
                .isInstanceOf(com.szpu.algohub.common.ApiException.class);
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(3,""),"100001",2))
                .isInstanceOf(com.szpu.algohub.common.ApiException.class);
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(0,""),"100001",2))
                .isInstanceOf(com.szpu.algohub.common.ApiException.class);
        var empty = LuoguRecordParser.parse(page(0,""),"100001",1);
        assertThat(empty.submissions()).isEmpty();
        assertThat(empty.reachedHistoryStart()).isTrue();
    }
    @Test void rejectsNumericOverflowInsteadOfWrappingIntoValidValues() {
        String valid = page(1,row(1,12,"100001",1787000000));
        for (String malformed : java.util.List.of(
                valid.replace("\"count\":1", "\"count\":4294967297"),
                valid.replace("\"perPage\":3", "\"perPage\":4294967299"),
                valid.replace("\"status\":12", "\"status\":4294967308"),
                valid.replace("1787000000", "18446744075496551616"))) {
            assertThatThrownBy(() -> LuoguRecordParser.parse(malformed,"100001",1))
                    .isInstanceOf(com.szpu.algohub.common.ApiException.class);
        }
    }
    private String row(int id, int status, String uid, long time) {
        return "{\"id\":"+id+",\"status\":"+status+",\"user\":{\"uid\":\""+uid+"\"},\"submitTime\":"+time
                +",\"problem\":{\"pid\":\"P1001\",\"name\":\"A+B\",\"difficulty\":1}}";
    }
    private String page(int count, String rows) {
        return "{\"data\":{\"records\":{\"count\":"+count+",\"perPage\":3,\"result\":["+rows+"]}}}";
    }
    @Test void skipsWaButKeepsRepeatedAcIdsAndOriginalTimes() {
        var result = LuoguRecordParser.parse(page(4, row(4,12,"100001",1789000100)+","+
                row(3,14,"100001",1789000000)+","+row(2,12,"100001",1788000000)), "100001", 1);
        assertThat(result.submissions()).hasSize(2);
        assertThat(result.submissions().get(1).submittedAt()).isEqualTo(Instant.ofEpochSecond(1788000000));
        assertThat(result.submissions()).extracting(s -> s.problem().problemKey()).containsExactly("P1001","P1001");
        assertThat(result.nextCursor()).isEqualTo("2");
        assertThat(result.reachedHistoryStart()).isFalse();
    }
    @Test void embeddedPageAtEndCompletesHistory() {
        var result = LuoguRecordParser.parse("<script id='lentille-context' type='application/json'>"
                + page(4,row(1,12,"100001",1787000000))+"</script>","100001",2);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.reachedHistoryStart()).isTrue();
    }
    @Test void rejectsWrongAccountEvenWhenPageHeaderClaimsLoggedInUser() {
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(1,row(1,12,"999",1787000000)),"100001",1))
                .hasMessageContaining("UID");
    }
    @Test void rejectsChallengeMissingTimeAndIncompleteEmptyPage() {
        assertThatThrownBy(() -> LuoguRecordParser.parse("<html>challenge</html>","100001",1));
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(1,row(1,12,"100001",0)),"100001",1));
        assertThatThrownBy(() -> LuoguRecordParser.parse(page(4,""),"100001",1));
    }
}
