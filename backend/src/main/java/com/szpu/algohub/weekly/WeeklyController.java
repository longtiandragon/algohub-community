package com.szpu.algohub.weekly;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
    public ApiResult<?> schools() { return ApiResult.ok(publishing.schools(CurrentUser.requireId())); }

    @GetMapping("/{school}/roster")
    public ApiResult<?> roster(@PathVariable long school) {
        publishing.requirePublisher(CurrentUser.requireId(), school);
        return ApiResult.ok(publishing.roster(school));
    }

    @GetMapping("/{school}/preview")
    public ApiResult<?> preview(@PathVariable long school, @RequestParam String url) {
        publishing.requirePublisher(CurrentUser.requireId(), school);
        return ApiResult.ok(publishing.preview(school,source.preview(url)));
    }

    public record Assignment(String url, Map<Integer,Integer> targets, LocalDate weekStart, String audience,
                             List<Long> studentIds,String status, OffsetDateTime publishAt) {
        public Assignment(String url, Map<Integer,Integer> targets, LocalDate weekStart) {
            this(url,targets,weekStart,"ALL",List.of(),"PUBLISHED",null);
        }
    }

    private WeeklySource.Meta metadata(long school,Assignment input) {
        publishing.requirePublisher(CurrentUser.requireId(),school);
        if(input==null || input.url()==null) throw ApiException.validation("请粘贴比赛链接");
        return source.preview(input.url());
    }

    @PostMapping("/{school}/defaults")
    public ApiResult<?> defaults(@PathVariable long school,@RequestBody Assignment input) {
        publishing.saveDefaults(CurrentUser.requireId(),school,input,metadata(school,input));
        return ApiResult.ok(true);
    }

    @PostMapping("/{school}")
    public ApiResult<Long> publish(@PathVariable long school,@RequestBody Assignment input) {
        return ApiResult.ok(publishing.insert(CurrentUser.requireId(),school,input,metadata(school,input)));
    }

    @PutMapping("/{school}/{id}")
    public ApiResult<Long> edit(@PathVariable long school,@PathVariable long id,@RequestBody Assignment input) {
        return ApiResult.ok(publishing.update(CurrentUser.requireId(),school,id,input,metadata(school,input)));
    }

    @DeleteMapping("/{school}/{id}")
    public ApiResult<?> cancel(@PathVariable long school,@PathVariable long id) {
        publishing.cancel(CurrentUser.requireId(),school,id);
        return ApiResult.ok(true);
    }

    @GetMapping("/{school}")
    public ApiResult<?> list(@PathVariable long school) {
        long actor=CurrentUser.requireId();
        return ApiResult.ok(report.list(school,actor,publishing.managerOrMember(actor,school)));
    }

    @GetMapping("/{school}/overview")
    public ApiResult<?> overview(@PathVariable long school,@RequestParam LocalDate weekStart, @RequestParam(defaultValue="false") boolean allTime) {
        long actor=CurrentUser.requireId();
        return ApiResult.ok(report.overview(school,weekStart,actor,publishing.managerOrMember(actor,school),allTime));
    }

    @GetMapping("/{school}/{id}")
    public ApiResult<?> detail(@PathVariable long school,@PathVariable long id) {
        long actor=CurrentUser.requireId();
        return ApiResult.ok(report.detail(school,id,actor,publishing.managerOrMember(actor,school)));
    }
}
