package com.szpu.algohub.statistics;

import java.time.YearMonth;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.school.SchoolService;

@RestController
@RequestMapping("/api/stats")
public class MonthlyReportController {
    private final MonthlyReportMapper mapper;
    private final CampusRankingService ranking;
    private final SchoolService schools;
    public MonthlyReportController(MonthlyReportMapper mapper, CampusRankingService ranking, SchoolService schools) {
        this.mapper = mapper; this.ranking = ranking; this.schools = schools;
    }

    @GetMapping("/monthly-report")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String,Object>> report(@RequestParam String month) {
        Long userId = CurrentUser.requireId();
        YearMonth selected;
        try { selected = YearMonth.parse(month); }
        catch (DateTimeParseException e) { throw ApiException.validation("月份格式应为 YYYY-MM"); }
        if (selected.getYear() < 2000 || selected.isAfter(YearMonth.from(TimeSupport.today()))) {
            throw ApiException.validation("请选择 2000 年至当前月份之间的月份");
        }
        var from = selected.atDay(1);
        var to = selected.atEndOfMonth().isAfter(TimeSupport.today()) ? TimeSupport.today() : selected.atEndOfMonth();
        var platforms = mapper.platforms(userId, from, to);
        boolean available = platforms.stream().anyMatch(p -> Boolean.TRUE.equals(p.get("firstAcAvailable")));
        long activeDays = mapper.activeDays(userId, from, to);
        var membership = schools.myMemberships(userId).stream().filter(m -> "APPROVED".equals(m.getStatus())).findFirst().orElse(null);
        Integer year = membership == null ? null : membership.getEnrollmentYear();
        var rank = ranking.monthly(userId, selected, year);
        var result = new LinkedHashMap<String,Object>();
        result.put("month", month); result.put("from", from); result.put("to", to);
        result.put("generatedAt", Instant.now()); result.put("timezone", "Asia/Shanghai");
        result.put("newSolved", available ? platforms.stream().mapToLong(p -> ((Number)p.get("solved")).longValue()).sum() : null);
        result.put("activeDays", activeDays > 0 || available ? activeDays : null);
        result.put("platforms", platforms); result.put("enrollmentYear", year);
        result.put("myRank", rank.get("myRank") == null ? null : ((Map<?,?>)rank.get("myRank")).get("rank"));
        result.put("participantCount", rank.get("participantCount"));
        result.put("rankNote", rank.get("myRankNote"));
        result.put("note", "按北京时间自然月统计，仅含可核对账号的已同步提交数据；首次通过必须有可确认时间。"
                + "平台总量、活动值与快照增量不计入新增通过。历史不完整时统计可能偏少，名次按当前登记年级与隐私设置重新计算，不是历史榜单快照。");
        return ApiResult.ok(result);
    }
}
