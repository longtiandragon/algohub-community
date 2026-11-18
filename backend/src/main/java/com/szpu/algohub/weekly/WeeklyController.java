package com.szpu.algohub.weekly;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.*;

@RestController
@RequestMapping("/api/weekly")
public class WeeklyController {
    private final WeeklyPublishing publishing;
    private final WeeklySource source;
    private final WeeklyReport report;

    public WeeklyController(WeeklyPublishing publishing, WeeklySource source, WeeklyReport report) {
        this.publishing = publishing;
        this.source = source;
        this.report = report;
    }

    @GetMapping("/schools")
    public ApiResult<?> schools() {
        return ApiResult.ok(publishing.schools(CurrentUser.requireId()));
    }

    @GetMapping("/{school}/preview")
    public ApiResult<?> preview(@PathVariable long school, @RequestParam String url) {
        long actor = CurrentUser.requireId();
        publishing.requirePublisher(actor, school);
        var meta = source.preview(url);
        return ApiResult.ok(publishing.preview(school, meta));
    }

    public record Assignment(String url, Map<Integer,Integer> targets, LocalDate weekStart) {}

    @PostMapping("/{school}")
    public ApiResult<Long> publish(@PathVariable long school, @RequestBody Assignment assignment) {
        long actor = CurrentUser.requireId();
        publishing.requirePublisher(actor, school);
        if (assignment == null || assignment.url() == null) throw ApiException.validation("请粘贴比赛链接");
        // Keep network I/O outside the transaction. Recheck the actual grant under lock on insert.
        var meta = source.preview(assignment.url());
        return ApiResult.ok(publishing.insert(actor, school, assignment, meta));
    }

    @GetMapping("/{school}")
    public ApiResult<?> list(@PathVariable long school) {
        long actor = CurrentUser.requireId();
        return ApiResult.ok(report.list(school, actor, publishing.managerOrMember(actor,school)));
    }

    @GetMapping("/{school}/{id}")
    public ApiResult<?> detail(@PathVariable long school, @PathVariable long id) {
        long actor = CurrentUser.requireId();
        return ApiResult.ok(report.detail(school,id,actor,publishing.managerOrMember(actor,school)));
    }
}
