package com.szpu.algohub.weekly;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.PlatformRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeeklyAtCoderStandingsTest {
    private final PlatformHttpClient http = mock(PlatformHttpClient.class);
    private static final String BASE = "https://atcoder.jp/contests/abc480";
    private static final WeeklySource.Meta META = new WeeklySource.Meta("ATCODER","abc480","ABC480","ABC",BASE,
            List.of(new WeeklySource.Task("A","abc480_a")));

    @Test
    void keepsBothTimedPerformancesButRejectsGhostAndUnknownMarkers(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("session.txt"), "test=fake");
        var source = new WeeklySource(http,file.toString());
        String header = """
                {"TaskInfo":[{"TaskScreenName":"abc480_a","Assignment":"A"}],"StandingsData":[
                """;
        String user = """
                {"Rank":23,"UserScreenName":"Learner","TotalResult":{"Count":2,"Elapsed":366000000000,"Score":10000},
                "TaskResults":{"abc480_a":{"Count":2,"Score":10000,"Elapsed":66000000000,
                "Status":1,"Failure":1,"Penalty":1}},"Additional":%s}
                """;
        String regular = user.formatted("null");
        when(http.get(Platform.ATCODER,BASE + "/standings/json", Map.of(), "test=fake"))
                .thenReturn(header + regular + "]}");
        String virtual = user.formatted("{\"standings.virtualElapsed\":-1}");
        String ghost = user.formatted("{\"standings.virtualElapsed\":-2}");
        String future = user.formatted("{\"standings.virtualElapsed\":-3}");
        when(http.get(Platform.ATCODER,BASE + "/standings/virtual/json", Map.of(), "test=fake"))
                .thenReturn(header + String.join(",",virtual,ghost,regular,future) + "]}");
        String empty = "<small class='contest-duration'><time>2026-10-17 21:00:00+0900</time>"
                + "<time>2026-10-17 22:40:00+0900</time></small><p>No Submissions</p>";
        for (String handle : List.of("learner", "another")) {
            when(http.get(Platform.ATCODER, BASE + "/submissions?f.User=" + handle + "&lang=en&page=1", Map.of(), "test=fake"))
                    .thenReturn(empty);
        }
        var rows = source.results(META,"learner",null);
        assertThat(rows).extracting(WeeklySource.Row::kind).containsExactly("OFFICIAL","VIRTUAL");
        assertThat(rows.getLast().cells().get("A")).isEqualTo(new WeeklySource.Cell(true,1,100d,66L));
        assertThat(rows.getLast().score()).isEqualTo(100d);
        assertThat(rows.getLast().sourceRank()).isEqualTo(23);
        assertThat(rows.getLast().penalty()).isEqualTo(366);
        assertThat(source.results(META,"another",null)).isEmpty();
        verify(http).get(Platform.ATCODER,BASE + "/standings/virtual/json", Map.of(), "test=fake");
    }

    @Test
    void loginPageIsReportedAsExpiredAuthorization(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("session.txt"), "test=fake");
        var source = new WeeklySource(http,file.toString());
        when(http.get(Platform.ATCODER,BASE + "/standings/json", Map.of(), "test=fake"))
                .thenReturn("<form action='/login'><input type='password'></form>");
        assertThatThrownBy(() -> source.results(META,"learner",null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getKind()).isEqualTo(ErrorKind.CREDENTIAL_INVALID));
    }

    @Test
    @EnabledIfSystemProperty(named = "algohub.live-weekly-atcoder", matches = "true")
    @EnabledIfEnvironmentVariable(named = "ALGOHUB_ATCODER_STANDINGS_COOKIE_FILE", matches = ".+")
    void authorizedVirtualParticipantCanBeFound() {
        var config = new AppProperties();
        var source = new WeeklySource(new PlatformHttpClient(config,new PlatformRateLimiter(config)));
        var rows = source.results(source.preview(BASE),"longtian0828",null);
        var vp = rows.stream().filter(r -> r.kind().equals("VIRTUAL")).findFirst().orElseThrow();
        var solved = vp.cells().entrySet().stream().filter(e -> e.getValue().solved()).map(Map.Entry::getKey).sorted().toList();
        assertThat(solved).as("ABC480 VP verified tasks").containsExactly("A", "B", "C");
        var practice = rows.stream().filter(r -> r.kind().equals("PRACTICE")).findFirst().orElseThrow();
        assertThat(practice.cells().get("D")).isEqualTo(new WeeklySource.Cell(true, 1, 400d, null));
        assertThat(practice.cells().get("E")).isEqualTo(new WeeklySource.Cell(true, 0, 450d, null));
        assertThat(practice.cells()).doesNotContainKeys("A", "B", "C");
        System.out.println("ABC480 VP source rank " + vp.sourceRank() + ", solved " + solved);
    }
}
