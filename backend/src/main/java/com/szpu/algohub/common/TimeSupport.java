package com.szpu.algohub.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 原始时间统一按 UTC 保存；校内每日统计按 Asia/Shanghai 划分自然日。
 */
public final class TimeSupport {

    public static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private TimeSupport() {
    }

    public static LocalDate campusDate(Instant instant) {
        return instant.atZone(CAMPUS_ZONE).toLocalDate();
    }

    public static Instant campusDayStart(LocalDate date) {
        return date.atStartOfDay(CAMPUS_ZONE).toInstant();
    }

    public static LocalDate today() {
        return LocalDate.now(CAMPUS_ZONE);
    }
}
