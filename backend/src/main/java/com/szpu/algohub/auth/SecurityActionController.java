package com.szpu.algohub.auth;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiResult;

/** 敏感操作前确认本人：查可用方式、发邮箱验证码、核对后换一张一次性凭证。 */
@RestController
@RequestMapping("/api/auth/security-action")
public class SecurityActionController {

    private final SecurityActionService service;

    public SecurityActionController(SecurityActionService service) {
        this.service = service;
    }

    public record Request(@Size(max = 32) String purpose, @Size(max = 128) String password, @Size(max = 6) String code) {
    }

    @GetMapping
    public ApiResult<Map<String, Object>> methods() {
        return ApiResult.ok(service.methods(CurrentUser.requireId()));
    }

    @PostMapping("/code")
    public ApiResult<Void> send(@Valid @RequestBody Request request) {
        service.send(CurrentUser.requireId(), request.purpose());
        return ApiResult.ok();
    }

    @PostMapping("/verify")
    public ApiResult<Map<String, String>> verify(@Valid @RequestBody Request request) {
        return ApiResult.ok(Map.of("actionToken",
                service.verify(CurrentUser.requireId(), request.purpose(), request.password(), request.code())));
    }
}
