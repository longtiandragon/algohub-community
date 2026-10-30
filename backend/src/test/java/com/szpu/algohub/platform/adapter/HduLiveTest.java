package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.PlatformRateLimiter;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;

/** 显式开启才访问源站，不让常规回归测试依赖网络。 */
@EnabledIfSystemProperty(named = "algohub.live-hdu", matches = "true")
class HduLiveTest {
    @Test
    void publicHistoryMatchesProfileTotals() {
        var properties = new AppProperties();
        properties.getHttp().getMinIntervalMs().put("HDU", 1500L);
        var adapter = new HduAdapter(new PlatformHttpClient(properties, new PlatformRateLimiter(properties)));
        String handle = System.getProperty("algohub.live-hdu-handle");
        assertThat(handle).as("Provide your own algohub.live-hdu-handle").isNotBlank();
        var identity = adapter.resolveIdentity(handle, null);
        var context = new SyncContext(0L, identity.handle(), identity.platformUid(), null, null, null);
        var totals = adapter.fetchTotals(context);
        var page = adapter.fetchSubmissions(context);
        assertThat(page.hasMore()).isFalse(); // 此测试账号当前只有一页；变化时需复核。
        assertThat(page.reachedHistoryStart()).isTrue();
        assertThat(page.submissions()).hasSize(totals.submissionTotal());
        assertThat(page.submissions().stream().filter(s -> s.accepted()).map(s -> s.problem().problemKey()).distinct().count())
                .isEqualTo(totals.solvedTotal().longValue());
        System.out.printf("HDU live: submissions=%d, solved=%d, cursor=%s%n",
                page.submissions().size(), totals.solvedTotal(), page.nextCursor());
    }
}
