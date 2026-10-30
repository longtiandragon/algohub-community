package com.szpu.algohub.school;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.szpu.algohub.common.ApiResult;

@RestController
public class EnrollmentYearController {
    private final EnrollmentYearService service;
    public EnrollmentYearController(EnrollmentYearService service) { this.service=service; }
    @GetMapping("/api/auth/enrollment-years") public ApiResult<?> active() { return ApiResult.ok(service.active()); }
    @GetMapping("/api/admin/enrollment-years") @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<?> all() { return ApiResult.ok(service.all()); }
    @PostMapping("/api/admin/enrollment-years") @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<?> save(@RequestBody EnrollmentYearService.Setting setting) {
        service.save(setting);return ApiResult.ok(service.all());
    }
}
