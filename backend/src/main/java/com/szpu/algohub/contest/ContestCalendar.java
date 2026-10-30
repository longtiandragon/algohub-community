package com.szpu.algohub.contest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.szpu.algohub.platform.Platform;

/**
 * 把收藏的比赛写成 iCalendar（.ics），可以导入手机或电脑的日历。
 *
 * 几条必须守住的：
 * - 时间按 UTC 写（结尾的 Z），由日历软件换算成本地时区，不自己拼 +08:00；
 * - **源站没给结束时间就不写 DTEND**，不用「默认两小时」这种猜测糊上去，
 *   日历里显示成一个时间点，说明写在 DESCRIPTION 里；
 * - 文本字段按 RFC 5545 转义（反斜杠、分号、逗号、换行），行按 75 字节折行，
 *   否则标题里一个逗号就能让整份日历导入失败；
 * - 导出的是**当次快照**：源站后来改期，已导入的日程不会自己更新，需要重新导出。
 */
final class ContestCalendar {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** 开赛前 30 分钟提醒，导出说明里会写明。 */
    private static final int ALARM_MINUTES = 30;

    private ContestCalendar() {
    }

    static byte[] build(List<Contest> contests, Instant now) {
        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//SZPU AlgoHub//contest calendar//ZH");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:" + escape("我收藏的比赛（SZPU AlgoHub）"));
        line(ics, "X-WR-TIMEZONE:Asia/Shanghai");

        for (Contest contest : contests) {
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:algohub-contest-" + contest.getId() + "@szpu-algohub.local");
            line(ics, "DTSTAMP:" + STAMP.format(now));
            line(ics, "DTSTART:" + STAMP.format(contest.getStartAt()));

            Instant end = endOf(contest);
            if (end != null) {
                line(ics, "DTEND:" + STAMP.format(end));
            }
            line(ics, "SUMMARY:" + escape(summary(contest)));
            if (contest.getUrl() != null && !contest.getUrl().isBlank()) {
                line(ics, "URL:" + escape(contest.getUrl()));
            }
            line(ics, "DESCRIPTION:" + escape(description(contest, end != null)));
            line(ics, "BEGIN:VALARM");
            line(ics, "ACTION:DISPLAY");
            line(ics, "TRIGGER:-PT" + ALARM_MINUTES + "M");
            line(ics, "DESCRIPTION:" + escape(summary(contest) + " 即将开始"));
            line(ics, "END:VALARM");
            line(ics, "END:VEVENT");
        }

        line(ics, "END:VCALENDAR");
        return ics.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 结束时间优先用源站给的，其次用源站给的时长推算；两者都没有就返回 null，不猜。 */
    private static Instant endOf(Contest contest) {
        if (contest.getEndAt() != null) {
            return contest.getEndAt();
        }
        if (contest.getDurationSec() != null && contest.getDurationSec() > 0) {
            return contest.getStartAt().plus(Duration.ofSeconds(contest.getDurationSec()));
        }
        return null;
    }

    /**
     * 标题前缀上平台名，方便在日历里一眼看出是哪家的比赛。
     * Codeforces 的标题本身就以 "Codeforces" 开头，这种就不再重复前缀，
     * 免得日历里显示成「Codeforces Codeforces Round 1121」。
     */
    private static String summary(Contest contest) {
        String platform;
        try {
            platform = Platform.parse(contest.getPlatform()).getDisplayName();
        } catch (RuntimeException e) {
            platform = contest.getPlatform();
        }
        String title = contest.getTitle() == null ? "" : contest.getTitle().strip();
        if (title.regionMatches(true, 0, platform, 0, platform.length())) {
            return title;
        }
        return platform + " " + title;
    }

    private static String description(Contest contest, boolean hasEnd) {
        StringBuilder text = new StringBuilder();
        if (contest.getUrl() != null && !contest.getUrl().isBlank()) {
            text.append(contest.getUrl()).append('\n');
        }
        if (!hasEnd) {
            text.append("源站没有给出结束时间与时长，这里只标出开始时间，不代表比赛只有一瞬间。\n");
        }
        if (contest.getCategory() != null && !contest.getCategory().isBlank()) {
            text.append("分类：").append(contest.getCategory()).append('\n');
        }
        text.append("由 SZPU AlgoHub 按收藏导出，时间来自各平台公开的比赛列表。")
                .append("这是导出当时的快照：源站之后改期或取消，已导入的日程不会自动更新，请重新导出。");
        return text.toString();
    }

    /** RFC 5545：反斜杠、分号、逗号要转义，换行写成 \\n。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /**
     * 按 RFC 5545 折行：每行最多 75 个八位字节，续行以一个空格开头。
     * 按字节数而不是字符数切，且不在 UTF-8 多字节序列中间切断，
     * 否则中文标题会被拦腰截断成乱码。
     */
    private static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int start = 0;
        boolean first = true;
        while (start < bytes.length) {
            int budget = first ? 75 : 74; // 续行首位要留给那个空格
            int end = Math.min(start + budget, bytes.length);
            // 退回到一个字符边界：UTF-8 续字节形如 10xxxxxx
            while (end > start && end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            if (!first) {
                out.append(' ');
            }
            out.append(new String(bytes, start, end - start, StandardCharsets.UTF_8));
            out.append("\r\n");
            start = end;
            first = false;
        }
        if (bytes.length == 0) {
            out.append("\r\n");
        }
    }
}
