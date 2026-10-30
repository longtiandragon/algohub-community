package com.szpu.algohub.activity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.school.SchoolService;

/**
 * 刷题动态。
 *
 * 动态条目就是“某人第一次通过某题”的记录，时间取 earliest_known_ac_at；
 * 当历史尚未回填完整时该时间只是“当前已知最早”，条目会标出可信度，不冒充精确首次 AC。
 * 新记录在对应账号下一次同步入库后才出现，不是实时推送。
 */
@Service
public class ActivityService {

    private static final int MAX_PAGE = 100;

    private final ActivityMapper activityMapper;
    private final com.szpu.algohub.social.SocialService social;
    private final SchoolService schoolService;
    private final PlatformRegistry registry;

    public ActivityService(ActivityMapper activityMapper, SchoolService schoolService, PlatformRegistry registry, com.szpu.algohub.social.SocialService social) {
        this.social = social;
        this.activityMapper = activityMapper;
        this.schoolService = schoolService;
        this.registry = registry;
    }

    private boolean recentOnly(Object platform) {
        try {
            return platform != null && registry.find(Platform.parse(platform.toString()))
                    .map(adapter -> adapter.partialHistoryNote() != null).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record FeedPage(List<Map<String, Object>> items, String nextCursor, boolean hasMore, String note) {
    }
    public List<Map<String,Object>> ownCoverage(Long userId) {
        return activityMapper.ownCoverage(userId);
    }

    /** 不分届的校内动态（原来的调用方式，行为不变）。 */
    public FeedPage feed(Long viewerId, String platform, Long filterUserId, String cursor, int limit) {
        return feed(viewerId, platform, filterUserId, null, cursor, limit);
    }

    /**
     * 校内刷题动态。个人的逐条提交（含未通过、可筛选可导出）在「我的档案 → 刷题历史与导出」，这里不重复提供。
     * year 不为空时只看这一届（学校成员登记的入学年级）的同学，隐私过滤照旧。
     */
    public FeedPage feed(Long viewerId, String platform, Long filterUserId, Integer year, String cursor, int limit) {
        return feed(viewerId, platform, filterUserId, "SCHOOL", year == null ? null : List.of(year), cursor, limit);
    }

    public FeedPage feed(Long viewerId, String platform, Long filterUserId, String scope, List<Integer> years, String cursor, int limit) {
        return feed(viewerId,platform,filterUserId,scope,years,cursor,limit,false);
    }

    public FeedPage feed(Long viewerId, String platform, Long filterUserId, String scope, List<Integer> years, String cursor, int limit, boolean followingOnly) {
        validateScope(scope, years);
        String year = yearFilter(years);
        int size = Math.min(Math.max(limit, 1), MAX_PAGE);
        Cursor parsed = Cursor.parse(cursor);
        String platformFilter = StringUtils.hasText(platform) ? platform : null;
        // 学校只按服务端成员记录确定；没有学校就没有"校内"可看
        Long schoolId = "PUBLIC".equals(scope) ? null : schoolService.approvedSchoolId(viewerId);
        if (schoolId == null && "SCHOOL".equals(scope)) {
            return new FeedPage(List.of(), null, false, "请先选择学校后查看校内动态。");
        }

        List<Map<String, Object>> rows =
                activityMapper.filteredFeed(schoolId, year, platformFilter, filterUserId, parsed.at(), parsed.id(), size + 1, followingOnly ? viewerId : null);

        boolean hasMore = rows.size() > size;
        List<Map<String, Object>> items = new ArrayList<>(hasMore ? rows.subList(0, size) : rows);
        String nextCursor = null;
        if (hasMore) {
            Map<String, Object> last = items.get(items.size() - 1);
            nextCursor = new Cursor(toInstant(last.get("acAt")), toLong(last.get("id"))).encode();
        }
        var following = social.followingIds(viewerId);
        items.forEach(row -> { decorate(row); row.put("following", following.contains(toLong(row.get("userId")))); });

        String note = ("PUBLIC".equals(scope) ? "公开动态包含开启公开动态的同学。" : "校内动态包含本校同学。")
                + "只包含已验证账号；关闭排行展示或档案仅自己可见的同学不出现。"
                + "新记录在对应账号下一次同步后出现，不是实时推送。"
                + "想看自己的全部提交（含未通过）或导出记录，请到「我的档案 → 刷题历史与导出」。";
        return new FeedPage(items, nextCursor, hasMore, note);
    }

    /** 给每条动态补一句可读描述与可信度说明，避免前端各自拼接口径不一致的文案。 */
    private void decorate(Map<String, Object> row) {
        String confidence = String.valueOf(row.getOrDefault("firstAcConfidence", "UNKNOWN"));
        row.put("timeAccuracy", switch (confidence) {
            case "EXACT" -> "首次通过时间准确";
            case "USER_REPORTED" -> "浏览器自报时间，未由源站独立核验";
            // 只公开最近通过记录的平台（力扣）：这是最近一次通过的时间，历史永远补不全，别写成"可能未回填"
            case "EARLIEST_KNOWN" -> recentOnly(row.get("platform"))
                    ? "这是最近一次通过的时间，不一定是第一次：该平台只公开最近通过的题"
                    : "当前已知最早通过时间，历史可能未回填完整";
            default -> "通过时间未知";
        });
        // 页面上放在时间旁边的短标签；完整说明在 timeAccuracy 里
        row.put("timeTag", switch (confidence) {
            case "EXACT" -> null;
            case "USER_REPORTED" -> "浏览器自报";
            case "EARLIEST_KNOWN" -> recentOnly(row.get("platform")) ? "最近一次通过" : "最早已知时间";
            default -> "时间未知";
        });
        row.put("summary", row.get("displayName") + " 通过了 " + row.get("platform") + " "
                + row.getOrDefault("problemKey", "") + " " + row.getOrDefault("title", ""));
    }

    public Map<String, Object> feedSummary(Long viewerId) {
        return feedSummary(viewerId, null);
    }

    /** 近 7 天的在练人数和通过题数；year 不为空时只算这一届。 */
    public Map<String, Object> feedSummary(Long viewerId, Integer year) {
        return feedSummary(viewerId, "SCHOOL", year == null ? null : List.of(year));
    }

    private static String yearFilter(List<Integer> years) {
        return years == null || years.isEmpty() ? null : years.stream().distinct().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }
    private static void validateScope(String scope, List<Integer> years) {
        if (!List.of("PUBLIC", "SCHOOL").contains(scope) || (years != null &&
            (years.size() > 101 || years.stream().anyMatch(y -> y == null || y < 2000 || y > 2100))))
            throw ApiException.validation("动态范围或年级无效");
    }

    public Map<String, Object> feedSummary(Long viewerId, String scope, List<Integer> years) {
        validateScope(scope, years);
        String year = yearFilter(years);
        Instant since = Instant.now().minus(Duration.ofDays(7));
        Long schoolId = "PUBLIC".equals(scope) ? null : schoolService.approvedSchoolId(viewerId);
        boolean unavailable = "SCHOOL".equals(scope) && schoolId == null;
        Map<String, Object> summary = unavailable ? null : activityMapper.feedSummary(schoolId, year, since);
        Map<String, Object> result = new LinkedHashMap<>(summary == null ? Map.of() : summary);
        result.put("windowDays", 7);
        result.put("year", years != null && years.size() == 1 ? years.getFirst() : null);
        result.put("years", years == null ? List.of() : years);
        // 动态里有哪些平台（不限时间）、哪些平台绑了但拿不到通过时间：页面据此写清动态包含什么
        List<Map<String, Object>> feedPlatforms = unavailable ? List.of() : activityMapper.feedPlatforms(schoolId, year);
        java.util.Set<String> inFeed = new java.util.HashSet<>();
        feedPlatforms.forEach(row -> inFeed.add(String.valueOf(row.get("platform"))));
        result.put("platforms", feedPlatforms);
        result.put("untimedPlatforms", unavailable ? List.of()
                : activityMapper.boardPlatforms(schoolId, year).stream().filter(p -> !inFeed.contains(p)).sorted().toList());
        result.put("note", unavailable ? "请先选择学校后查看校内动态"
                : "近 7 天，" + ("PUBLIC".equals(scope) ? "公开动态" : "校内动态")
                  + (year == null ? "，全部年级" : "，" + year.replace(",", "、") + " 级")
                  + "参与展示的同学的首次通过记录");
        return result;
    }

    /** 连续刷题天数：按 Asia/Shanghai 自然日，今天还没刷时从昨天开始算，不强行断掉。 */
    public Map<String, Object> streak(Long userId) {
        return streak(userId, false);
    }

    public Map<String, Object> streak(Long userId, boolean verifiedOnly) {
        List<Map<String, Object>> days = activityMapper.dailySolveDays(userId, verifiedOnly);
        Map<String, Object> result = new LinkedHashMap<>();
        if (days.isEmpty()) {
            result.put("currentStreak", 0);
            result.put("longestStreak", 0);
            result.put("activeDays", 0);
            result.put("solvedToday", 0);
            result.put("note", "还没有带时间的通过记录");
            return result;
        }
        List<LocalDate> dates = days.stream().map(d -> toLocalDate(d.get("day"))).filter(java.util.Objects::nonNull)
                .toList();
        LocalDate today = TimeSupport.today();
        int solvedToday = days.stream()
                .filter(d -> today.equals(toLocalDate(d.get("day"))))
                .mapToInt(d -> ((Number) d.get("solved")).intValue())
                .sum();

        int current = 0;
        LocalDate expect = dates.get(0).equals(today) ? today : today.minusDays(1);
        for (LocalDate date : dates) {
            if (date.equals(expect)) {
                current++;
                expect = expect.minusDays(1);
            } else if (date.isBefore(expect)) {
                break;
            }
        }
        int longest = 1;
        int run = 1;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i).plusDays(1).equals(dates.get(i - 1))) {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 1;
            }
        }
        result.put("currentStreak", current);
        result.put("longestStreak", Math.max(longest, current));
        result.put("activeDays", dates.size());
        result.put("solvedToday", solvedToday);
        result.put("note", "按 Asia/Shanghai 自然日统计首次通过；只统计有已知通过时间的记录");
        return result;
    }

    // ---------- 工具 ----------

    private record Cursor(Instant at, Long id) {
        static Cursor parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                return new Cursor(null, null);
            }
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(raw),
                        java.nio.charset.StandardCharsets.UTF_8);
                int idx = decoded.lastIndexOf('|');
                return new Cursor(Instant.parse(decoded.substring(0, idx)),
                        Long.valueOf(decoded.substring(idx + 1)));
            } catch (Exception e) {
                throw ApiException.validation("分页游标无效");
            }
        }

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((at + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return null;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return null;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

}
