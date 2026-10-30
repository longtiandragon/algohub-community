package com.szpu.algohub.reminder;

import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/admin/reminders")
public class AdminReminderController {
    private final StudyReminderService service;
    public AdminReminderController(StudyReminderService service) { this.service=service; }
    @GetMapping("/users") public ApiResult<?> users(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") long after) {
        return ApiResult.ok(service.members(search,after));
    }
    @GetMapping("/users/{id}") public ApiResult<?> current(@PathVariable long id) { return ApiResult.ok(service.current(id)); }
    @PostMapping("/users/{id}/goals") public ApiResult<?> assign(@PathVariable long id,@RequestBody StudyReminderService.AssignmentRequest request) {
        return ApiResult.ok(service.assign(CurrentUser.requireId(),id,request));
    }
    @PostMapping("/users/{id}/send") public ApiResult<?> send(@PathVariable long id,@RequestBody StudyReminderService.ManualRequest request) {
        return ApiResult.ok(service.manual(CurrentUser.requireId(),id,request));
    }
    @GetMapping("/users/{id}/deliveries") public ApiResult<?> deliveries(@PathVariable long id) { return ApiResult.ok(service.deliveries(id)); }
}
