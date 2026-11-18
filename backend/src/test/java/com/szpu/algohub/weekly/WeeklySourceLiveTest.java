package com.szpu.algohub.weekly;

import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.PlatformRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "algohub.live-weekly", matches = "true")
class WeeklySourceLiveTest {
    private final AppProperties properties = new AppProperties();
    private final WeeklySource source = new WeeklySource(new PlatformHttpClient(properties,new PlatformRateLimiter(properties)));

    @Test
    void codeforcesContestVirtualAndPracticeMatchActualStandings() {
        var meta = source.preview("https://codeforces.com/contest/2278");
        assertThat(meta.category()).isEqualTo("Div.3");
        assertThat(meta.tasks()).hasSize(7);
        var official = source.results(meta,"longtian0828",null);
        assertThat(official.stream().filter(r -> r.kind().equals("OFFICIAL")).findFirst().orElseThrow().sourceRank()).isPositive();
        assertThat(solved(official,"OFFICIAL")).containsExactlyInAnyOrder("B","C","D","E");
        assertThat(solved(official,"PRACTICE")).contains("A","F");
        var virtual = source.results(meta,"acmfht",null);
        assertThat(solved(virtual,"VIRTUAL")).containsExactlyInAnyOrder("A","B","C","D");
        assertThat(virtual.stream().filter(r -> r.kind().equals("PRACTICE")).findFirst().orElseThrow()
                .cells().get("E")).isEqualTo(new WeeklySource.Cell(true,2,null,null));
    }

    @Test
    void nowcoderStarLatePageAndPracticeAreSeparate() {
        var meta = source.preview("https://ac.nowcoder.com/acm/contest/141882#rank/%22rankScope%22%3A%22ALL%22");
        assertThat(meta.title()).isEqualTo("牛客小白月赛139");
        assertThat(meta.category()).isEqualTo("小白月赛");
        assertThat(meta.tasks()).hasSize(6);
        var star = source.results(meta,"jimmywang","996283503");
        assertThat(star.getFirst().sourceRank()).isEqualTo(1);
        assertThat(solved(star,"STAR")).hasSize(6);
        assertThat(star.getFirst().cells().get("D").wrong()).isEqualTo(1);
        assertThat(solved(source.results(meta,"","544260047"),"OFFICIAL")).contains("A");
        assertThat(solved(source.results(meta,"","932635830"),"PRACTICE")).contains("C","E");
    }

    @Test
    void atcoderImportsTasksWithoutAuthentication() {
        var meta = source.preview("https://atcoder.jp/contests/abc484");
        assertThat(meta.category()).isEqualTo("ABC");
        assertThat(meta.tasks()).extracting(WeeklySource.Task::index).containsExactly("A","B","C","D","E","F","G");
    }

    private static Set<String> solved(List<WeeklySource.Row> rows,String kind) {
        return rows.stream().filter(row -> row.kind().equals(kind)).flatMap(row -> row.cells().entrySet().stream())
                .filter(entry -> entry.getValue().solved()).map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
    }
}
