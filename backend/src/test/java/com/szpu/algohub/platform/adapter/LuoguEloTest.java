package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class LuoguEloTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode fixture() throws Exception {
        try (var source = getClass().getResourceAsStream("/samples/luogu/practice-normal.json")) {
            return (ObjectNode) json.readTree(new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void snapshotUsesContestEloAndPreservesUnknownFirstChange() throws Exception {
        var snapshot = LuoguPracticeParser.parse(fixture().toString(), "100001");
        assertThat(snapshot.ratedContests()).extracting(RatedContest::contestKey, RatedContest::oldRating,
                RatedContest::newRating).containsExactly(tuple("256235", 0, 512), tuple("236568", null, 0));
        assertThat(snapshot.ratedContests().get(0).finishedAt().getEpochSecond()).isEqualTo(1766914200L);
    }

    @Test
    void distinguishesNoRatedContestsFromUnavailableOrIncompleteHistory() throws Exception {
        var root = fixture();
        var data = (ObjectNode) root.get("data");
        ((ArrayNode) data.get("elo")).remove(1);
        assertThat(LuoguPracticeParser.parse(root.toString(), "100001").ratedContests()).isNull();
        assertThatThrownBy(() -> LuoguPracticeParser.elo(root.toString(), "100001")).isInstanceOf(ApiException.class);
        data.putArray("elo");
        assertThat(LuoguPracticeParser.elo(root.toString(), "100001")).isEmpty();
        data.remove("elo");
        assertThat(LuoguPracticeParser.parse(root.toString(), "100001").ratedContests()).isNull();
    }
}
