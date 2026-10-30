package com.szpu.algohub.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.school.SchoolService;

/**
 * 校内训练活动。
 *
 * 管理员发一段有起止时间的训练，关联一个题单；同学的进度**全部实时算**，不需要报名，
 * 也不存任何完成标记：题单里的题，通过时间落在活动窗口内的算「活动期间完成」，
 * 早于开始时间的算「活动前已通过」，两者分开摆——开赛前就会的题不算训练成果。
 *
 * 通过时间未知的记录（例如洛谷题单、Tracker 缓存）两边都不计入，单独报数，
 * 免得同学以为平台漏算了。
 */
@Service
public class TrainingActivityService {

    private static final int MAX_TITLE = 128;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_LIST = 50;
    private static final int LEADERBOARD_LIMIT = 20;

    private final TrainingActivityMapper mapper;
    private final SchoolService schoolService;

    public TrainingActivityService(TrainingActivityMapper mapper, SchoolService schoolService) {
        this.mapper = mapper;
        this.schoolService = schoolService;
    }

    /**
     * scope：SCHOOL 只对 schoolId 这所学校可见（默认）；PUBLIC 全站可见。
     * 学校编号只在管理员接口里由管理员指定，学生侧一律按服务端成员记录判断。
     */
    public record SaveRequest(String title, String description, Long listId, Instant startAt,
                              Instant endAt, Boolean published, String scope, Long schoolId) {
    }

    // ---------- 管理员 ----------

    public Map<String, Object> create(Long adminId, SaveRequest request) {
        validate(request);
        String scope = scope(request);
        mapper.insert(request.title().strip(), description(request), request.listId(),
                request.startAt(), request.endAt(), Boolean.TRUE.equals(request.published()), adminId,
                scope, schoolFor(scope, request));
        return Map.of("created", true);
    }

    public Map<String, Object> update(Long id, SaveRequest request) {
        requireExists(id);
        validate(request);
        String scope = scope(request);
        mapper.update(id, request.title().strip(), description(request), request.listId(),
                request.startAt(), request.endAt(), Boolean.TRUE.equals(request.published()),
                scope, schoolFor(scope, request));
        return detailForAdmin(id);
    }

    private static String scope(SaveRequest request) {
        String scope = StringUtils.hasText(request.scope()) ? request.scope().trim().toUpperCase() : "SCHOOL";
        if (!List.of("SCHOOL", "PUBLIC").contains(scope)) {
            throw ApiException.validation("活动范围只能是 SCHOOL（指定学校）或 PUBLIC（全站）");
        }
        return scope;
    }

    private Long schoolFor(String scope, SaveRequest request) {
        if ("PUBLIC".equals(scope)) {
            return null;
        }
        if (request.schoolId() == null || schoolService.findSchool(request.schoolId()) == null) {
            throw ApiException.validation("面向学校的活动需要选择一所有效的学校");
        }
        return request.schoolId();
    }

    public Map<String, Object> delete(Long id) {
        requireExists(id);
        mapper.delete(id);
        return Map.of("deleted", true,
                "note", "只删除了活动本身；题单、同学的通过记录都不受影响。");
    }

    private void validate(SaveRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw ApiException.validation("请填写活动标题");
        }
        if (request.title().strip().length() > MAX_TITLE) {
            throw ApiException.validation("活动标题最多 " + MAX_TITLE + " 字");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION) {
            throw ApiException.validation("活动说明最多 " + MAX_DESCRIPTION + " 字");
        }
        if (request.startAt() == null || request.endAt() == null) {
            throw ApiException.validation("请填写活动的开始与结束时间");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw ApiException.validation("结束时间必须晚于开始时间");
        }
    }

    private String description(SaveRequest request) {
        if (request.description() == null) {
            return null;
        }
        String text = request.description().strip();
        return text.isEmpty() ? null : text;
    }

    private void requireExists(Long id) {
        if (id == null || mapper.exists(id) == null) {
            throw ApiException.notFound("活动不存在或已被删除");
        }
    }

    // ---------- 学生与管理员共用 ----------

    /**
     * 活动列表。学生只看到已发布、且面向全站或面向本校的活动；管理员看到全部，含未发布的草稿与其他学校的活动。
     */
    public Map<String, Object> list(Long userId, boolean isAdmin) {
        Long schoolId = schoolService.approvedSchoolId(userId);
        List<Map<String, Object>> rows = mapper.list(isAdmin, schoolId, MAX_LIST);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        Instant now = Instant.now();
        for (Map<String, Object> row : rows) {
            items.add(decorate(row, userId, schoolId, now, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("note", note());
        return result;
    }

    public Map<String, Object> detail(Long userId, Long id, boolean isAdmin) {
        Map<String, Object> row = mapper.find(id);
        Long schoolId = schoolService.approvedSchoolId(userId);
        if (row == null || (!isAdmin && !visibleToStudent(row, schoolId))) {
            // 其他学校的活动与未发布的活动一样按"不存在"处理，不透露是否存在
            throw ApiException.notFound("活动不存在或尚未发布");
        }
        Map<String, Object> result = decorate(row, userId, schoolId, Instant.now(), true);
        result.put("note", note());
        return result;
    }

    private static boolean visibleToStudent(Map<String, Object> row, Long schoolId) {
        if (!Boolean.TRUE.equals(row.get("published"))) {
            return false;
        }
        return "PUBLIC".equals(row.get("scope"))
                || (schoolId != null && schoolId.equals(toLong(row.get("schoolId"))));
    }

    private Map<String, Object> detailForAdmin(Long id) {
        Map<String, Object> row = mapper.find(id);
        return row == null ? Map.of() : new LinkedHashMap<>(row);
    }

    /** 补上状态、我的进度，以及（详情页才要的）逐题状态与本校活动榜。 */
    private Map<String, Object> decorate(Map<String, Object> row, Long userId, Long schoolId, Instant now,
                                         boolean withDetail) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        Instant startAt = toInstant(row.get("startAt"));
        Instant endAt = toInstant(row.get("endAt"));
        item.put("state", state(startAt, endAt, now));

        Long listId = toLong(row.get("listId"));
        if (listId == null) {
            item.put("progress", Map.of());
            item.put("progressNote", "这个活动还没有关联题单，暂时算不出进度。");
            if (withDetail) {
                item.put("problems", List.of());
                item.put("leaderboard", List.of());
            }
            return item;
        }

        Map<String, Object> progress = mapper.progress(userId, listId, startAt, endAt);
        item.put("progress", progress == null ? Map.of() : progress);
        if (withDetail) {
            item.put("problems", mapper.problems(userId, listId, startAt, endAt));
            // 活动榜只列本校同学；没有学校（例如未加入学校的管理员）时不给榜单
            item.put("leaderboard", schoolId == null ? List.of()
                    : mapper.leaderboard(schoolId, listId, startAt, endAt, LEADERBOARD_LIMIT));
        }
        return item;
    }

    private String state(Instant startAt, Instant endAt, Instant now) {
        if (startAt == null || endAt == null) {
            return "UNKNOWN";
        }
        if (now.isBefore(startAt)) {
            return "UPCOMING";
        }
        return now.isBefore(endAt) ? "RUNNING" : "ENDED";
    }

    private String note() {
        return "进度按「题单里的题、通过时间落在活动起止之间」实时统计，不需要报名，也不存完成标记；"
                + "活动开始前就通过的题单独计为「活动前已通过」，不算作本次训练的成果；"
                + "源站没给通过时间的记录两边都不计入，会单独报数；"
                + "新通过的题要等对应账号同步之后才会出现，不是实时的。"
                + "活动榜只列本校参与校内展示、账号已验证归属的同学，与排行榜口径一致；全站活动也只看本校。";
    }

    private static Instant toInstant(Object value) {
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

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
