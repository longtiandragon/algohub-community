package com.szpu.algohub.platform;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

/**
 * 按平台共享的最小请求间隔。所有账号和任务共同遵守源站限制。
 *
 * 项目开了虚拟线程：在 synchronized 里 sleep 会钉住载体线程，排队的人一多就把全站卡死。
 * 所以只在锁内“预约”下一个可用时间点（很快），睡眠放到锁外；排队太久的请求直接拒绝，不占线程。
 */
@Component
public class PlatformRateLimiter {

    static final Duration DEFAULT_MAX_QUEUE_WAIT = Duration.ofSeconds(30);

    private final ReentrantLock lock = new ReentrantLock();
    /** 每个平台下一次可以发请求的时间点（毫秒，与 clock 同一时基） */
    private final Map<String, Long> nextFreeAt = new ConcurrentHashMap<>();
    private final AppProperties properties;
    private final long maxQueueWaitMs;
    private final LongSupplier clock;

    @Autowired
    public PlatformRateLimiter(AppProperties properties) {
        this(properties, DEFAULT_MAX_QUEUE_WAIT, System::currentTimeMillis);
    }

    PlatformRateLimiter(AppProperties properties, Duration maxQueueWait, LongSupplier clock) {
        this.properties = properties;
        this.maxQueueWaitMs = maxQueueWait.toMillis();
        this.clock = clock;
    }

    public void acquire(Platform platform) {
        long wait = reserve(platform);
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步线程被中断", e);
        }
    }

    /** 锁内只做计算：返回需要等待的毫秒数，并占下那个时间点。排队超过上限时不占位、直接拒绝。 */
    long reserve(Platform platform) {
        long minInterval = properties.getHttp().getMinIntervalMs()
                .getOrDefault(platform.name(), 2000L);
        lock.lock();
        try {
            long now = clock.getAsLong();
            long slot = Math.max(now, nextFreeAt.getOrDefault(platform.name(), 0L));
            long wait = slot - now;
            if (wait > maxQueueWaitMs) {
                throw new ApiException(ErrorKind.RATE_LIMITED, "平台请求排队太久，请稍后再试");
            }
            nextFreeAt.put(platform.name(), slot + minInterval);
            return wait;
        } finally {
            lock.unlock();
        }
    }
}
