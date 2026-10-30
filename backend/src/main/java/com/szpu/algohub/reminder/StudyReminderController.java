package com.szpu.algohub.reminder;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/reminders")
public class StudyReminderController {
    private final StudyReminderService service;
    private final StudyReminderJobs jobs;
    public StudyReminderController(StudyReminderService service,StudyReminderJobs jobs) { this.service=service;this.jobs=jobs; }
    @GetMapping("/status") public ApiResult<?> status() { return ApiResult.ok(jobs.status()); }
    @GetMapping("/goals") public ApiResult<?> goals() { return ApiResult.ok(service.current(CurrentUser.requireId())); }
    @PostMapping("/goals") public ApiResult<?> save(@RequestBody StudyReminderService.PersonalRequest request) {
        return ApiResult.ok(service.save(CurrentUser.requireId(),request));
    }
    @GetMapping("/inbox") public ApiResult<?> inbox(@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="ALL") String category) {
        return ApiResult.ok(service.inbox(CurrentUser.requireId(),before,category));
    }
    @GetMapping("/unread") public ApiResult<?> unread() { return ApiResult.ok(Map.of("unread",service.unread(CurrentUser.requireId()))); }
    @PostMapping("/inbox/{id}/read") public ApiResult<?> read(@PathVariable long id) {
        service.read(CurrentUser.requireId(),id);return ApiResult.ok(Map.of());
    }
    @PostMapping("/inbox/read-all") public ApiResult<?> readAll(@RequestParam(defaultValue="ALL") String category) {
        service.readAll(CurrentUser.requireId(),category);return ApiResult.ok(Map.of());
    }
}
