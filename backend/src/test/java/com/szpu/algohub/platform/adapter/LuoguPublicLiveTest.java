package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.PlatformRateLimiter;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;

/** Explicit read-only source check. No Spring context, database, or saved credentials. */
@EnabledIfSystemProperty(named = "luogu.live.uid", matches = "[1-9][0-9]*")
class LuoguPublicLiveTest {
    @Test
    void anonymousPracticeReturnsConsistentPassedProblems() {
        String uid = System.getProperty("luogu.live.uid");
        var properties = new AppProperties();
        var http = new PlatformHttpClient(properties, new PlatformRateLimiter(properties));
        var adapter = new LuoguAdapter(http);
        var result = adapter.fetchTotals(new SyncContext(null, uid, uid, null, null, null));
        assertThat(result.solvedTotal()).isNotNull().isNotNegative();
        assertThat(result.solvedProblems()).hasSize(result.solvedTotal());
        assertThat(result.solvedProblems()).extracting(problem -> problem.problemKey()).doesNotHaveDuplicates();
        System.out.printf("LIVE_PUBLIC_LUOGU uid=%s solved=%d unique=%d activityDays=%d checkedAt=%s cookie=NONE%n",
                uid, result.solvedTotal(), result.solvedProblems().size(), result.dailyActivity().size(), Instant.now());
    }
}
