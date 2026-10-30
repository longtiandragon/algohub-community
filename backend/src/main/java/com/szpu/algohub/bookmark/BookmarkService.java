package com.szpu.algohub.bookmark;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;

/**
 * 题目收藏与复习记录。
 *
 * 这里保存的全部是**自己声明的意图**：想做、想复习、自己认为过关了。
 * 它不参与任何通过量统计，也不改写通过记录——标「待复习」不代表通过过，
 * 取消收藏也不会动真实的 AC 数据。页面上同时显示平台记录的「是否已通过」，
 * 两者分开摆，不用收藏次数去推断掌握程度。
 *
 * 笔记只有本人能读：所有查询都带 user_id，没有按他人 ID 查询的入口。
 */
@Service
public class BookmarkService {

    /** LATER 稍后再做；REVIEW 待复习；DONE 自己认为过关了。都是自报状态。 */
    private static final Set<String> STATUSES = Set.of("LATER", "REVIEW", "DONE");
    private static final int MAX_NOTE = 500;
    private static final int MAX_PAGE_SIZE = 100;
    /** 单人收藏上限，避免一个账号把表撑爆；到顶时明确提示而不是静默丢弃。 */
    private static final long MAX_BOOKMARKS = 2000;

    private final BookmarkMapper mapper;

    public BookmarkService(BookmarkMapper mapper) {
        this.mapper = mapper;
    }

    public record SaveRequest(Long problemId, String status, String note, LocalDate remindOn) {
    }

    /** 新增或更新一条收藏。同一题重复收藏是幂等的：更新，不会多出一条。 */
    public Map<String, Object> save(Long userId, SaveRequest request) {
        if (request == null || request.problemId() == null) {
            throw ApiException.validation("请指定题目");
        }
        if (mapper.problemExists(request.problemId()) == 0) {
            throw ApiException.notFound("题目不存在，可能题库还没同步到这道题");
        }
        String status = normalizeStatus(request.status());
        String note = request.note();
        if (note != null) {
            note = note.strip();
            if (note.length() > MAX_NOTE) {
                throw ApiException.validation("笔记最多 " + MAX_NOTE + " 字");
            }
            if (note.isEmpty()) {
                note = null;
            }
        }
        LocalDate remindOn = request.remindOn();
        if (remindOn != null && remindOn.isBefore(TimeSupport.today().minusYears(1))) {
            throw ApiException.validation("复习日期过早，请选择一年以内的日期");
        }
        if (remindOn != null && remindOn.isAfter(TimeSupport.today().plusYears(5))) {
            throw ApiException.validation("复习日期最多设置到 5 年以内");
        }

        boolean isNew = mapper.findOne(userId, request.problemId()) == null;
        if (isNew && mapper.count(userId, null, null, null, false, TimeSupport.today()) >= MAX_BOOKMARKS) {
            throw ApiException.validation("收藏已达到 " + MAX_BOOKMARKS + " 条上限，请先清理一些再收藏");
        }
        mapper.upsert(userId, request.problemId(), status, note, remindOn);

        Map<String, Object> saved = new LinkedHashMap<>(mapper.findOne(userId, request.problemId()));
        saved.put("created", isNew);
        return saved;
    }

    /** 取消收藏。只删自己的记录，不影响任何通过数据。 */
    public Map<String, Object> remove(Long userId, Long problemId) {
        if (problemId == null) {
            throw ApiException.validation("请指定题目");
        }
        int removed = mapper.remove(userId, problemId);
        return Map.of("removed", removed > 0,
                "note", "只删除了你的收藏与笔记；这道题的通过记录不受影响。");
    }

    public Map<String, Object> list(Long userId, String status, String platform, String category,
                                    boolean dueOnly, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.validation("页码必须为正，单页条数为 1～" + MAX_PAGE_SIZE);
        }
        String statusFilter = StringUtils.hasText(status) ? normalizeStatus(status) : null;
        String platformFilter = StringUtils.hasText(platform) ? Platform.parse(platform).name() : null;
        String categoryFilter = StringUtils.hasText(category) ? category.trim() : null;
        LocalDate today = TimeSupport.today();

        List<Map<String, Object>> rows = mapper.list(userId, statusFilter, platformFilter, categoryFilter,
                dueOnly, today, size, ((long) page - 1) * size);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("categories", splitCategories(row.get("categories")));
            item.put("due", isDue(row.get("remindOn"), today));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", mapper.count(userId, statusFilter, platformFilter, categoryFilter, dueOnly, today));
        result.put("page", page);
        result.put("size", size);
        result.put("counts", counts(userId, today));
        result.put("timezone", "Asia/Shanghai");
        result.put("note", "收藏与笔记都只有你自己能看到。状态是你自己标的，"
                + "「已通过」则来自平台同步记录，两者互不影响：标为「已过关」不会变成一条通过记录，"
                + "取消收藏也不会删掉真实的通过数据。复习日期只在站内提示，不发邮件或推送。");
        return result;
    }

    /** 各状态的条数，以及其中到了复习日期的条数。 */
    private Map<String, Object> counts(Long userId, LocalDate today) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : List.of("LATER", "REVIEW", "DONE")) {
            counts.put(status, Map.of("count", 0L, "due", 0L));
        }
        long total = 0;
        long due = 0;
        for (Map<String, Object> row : mapper.statusCounts(userId, today)) {
            String status = String.valueOf(row.get("status"));
            long count = ((Number) row.get("count")).longValue();
            long statusDue = ((Number) row.get("due")).longValue();
            counts.put(status, Map.of("count", count, "due", statusDue));
            total += count;
            due += statusDue;
        }
        counts.put("TOTAL", Map.of("count", total, "due", due));
        return counts;
    }

    /** 一批题目的收藏状态，供题目列表一次取回，避免每题一次请求。 */
    public Map<Long, String> statusOf(Long userId, List<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.findMany(userId, problemIds)) {
            result.put(((Number) row.get("problemId")).longValue(), String.valueOf(row.get("status")));
        }
        return result;
    }

    private String normalizeStatus(String status) {
        String value = status == null ? "LATER" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw ApiException.validation("状态只支持 LATER（稍后再做）、REVIEW（待复习）或 DONE（自己认为过关）");
        }
        return value;
    }

    private static List<String> splitCategories(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).sorted()
                .toList();
    }

    private static boolean isDue(Object remindOn, LocalDate today) {
        LocalDate date = toLocalDate(remindOn);
        return date != null && !date.isAfter(today);
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
}
