package com.szpu.algohub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

/** 平台限速：锁内预约、锁外睡眠；排队太久直接拒绝；虚拟线程大量排队不拖住其他平台。 */
class PlatformRateLimiterTest {

    private static AppProperties props(long intervalMs) {
        AppProperties properties = new AppProperties();
        properties.getHttp().getMinIntervalMs().put("CODEFORCES", intervalMs);
        properties.getHttp().getMinIntervalMs().put("ATCODER", intervalMs);
        return properties;
    }

    @Test
    void reservesConsecutiveSlotsAndRejectsBeyondQueueLimit() {
        AtomicLong now = new AtomicLong(1_000_000);
        PlatformRateLimiter limiter = new PlatformRateLimiter(props(2000), Duration.ofSeconds(30), now::get);
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            waits.add(limiter.reserve(Platform.CODEFORCES));
        }
        // 0, 2000, ..., 30000：正好 30 秒的还能排上
        assertThat(waits.getFirst()).isZero();
        assertThat(waits.getLast()).isEqualTo(30_000);
        assertThatThrownBy(() -> limiter.reserve(Platform.CODEFORCES))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("排队太久")
                .extracting(e -> ((ApiException) e).getKind()).isEqualTo(ErrorKind.RATE_LIMITED);
        // 被拒绝的请求不占位；另一个平台互不影响
        assertThat(limiter.reserve(Platform.ATCODER)).isZero();
        now.addAndGet(2000);
        assertThat(limiter.reserve(Platform.CODEFORCES)).isEqualTo(30_000);
    }

    @Test
    void concurrentVirtualThreadsAreSpacedAndDoNotPinOtherPlatforms() throws Exception {
        long interval = 100;
        PlatformRateLimiter limiter = new PlatformRateLimiter(props(interval), Duration.ofMillis(650),
                System::currentTimeMillis);
        int callers = 40;
        List<Long> startedAt = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        limiter.acquire(Platform.CODEFORCES);
                        startedAt.add(System.nanoTime());
                    } catch (ApiException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            // 大量虚拟线程在 CF 上排队时，另一个平台仍然立即放行
            long t0 = System.nanoTime();
            pool.submit(() -> limiter.acquire(Platform.ATCODER)).get(2, TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(500);
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }
        // 排队上限 650ms、间隔 100ms：约 7 个排上（0..600），其余立即拒绝，不占线程
        assertThat(startedAt.size()).isBetween(7, 12);
        assertThat(rejected.get()).isEqualTo(callers - startedAt.size());
        List<Long> sorted = new ArrayList<>(startedAt);
        Collections.sort(sorted);
        // 醒来可能略晚，逐个间隔会有抖动；整体跨度不会短于预约的间隔总和
        assertThat(TimeUnit.NANOSECONDS.toMillis(sorted.getLast() - sorted.getFirst()))
                .isGreaterThanOrEqualTo((sorted.size() - 1) * interval - 30);
    }
}
