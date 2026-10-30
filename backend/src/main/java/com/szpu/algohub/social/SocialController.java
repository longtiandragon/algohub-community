package com.szpu.algohub.social;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController @RequestMapping("/api/social")
public class SocialController {
    private final SocialService service;
    public SocialController(SocialService service) { this.service=service; }
    @GetMapping("/users/{id}") public ApiResult<?> state(@PathVariable long id) { return ApiResult.ok(service.state(CurrentUser.requireId(),id)); }
    @PostMapping("/users/{id}/follow") public ApiResult<?> follow(@PathVariable long id) { return ApiResult.ok(service.follow(CurrentUser.requireId(),id)); }
    @DeleteMapping("/users/{id}/follow") public ApiResult<?> unfollow(@PathVariable long id) { return ApiResult.ok(service.unfollow(CurrentUser.requireId(),id)); }
    @GetMapping("/connections") public ApiResult<?> connections(@RequestParam(defaultValue="FRIENDS") String type,@RequestParam(defaultValue="0") long before) {
        return ApiResult.ok(service.connections(CurrentUser.requireId(),type,before));
    }
    @GetMapping("/templates") public ApiResult<?> templates() { return ApiResult.ok(SocialService.TEMPLATES); }
    @GetMapping("/counts") public ApiResult<?> counts() { return ApiResult.ok(service.counts(CurrentUser.requireId())); }
    public record Message(@NotBlank @Size(max=32) String templateCode,@NotNull UUID requestId) {}
    @PostMapping("/users/{id}/messages") public ApiResult<?> send(@PathVariable long id,@Valid @RequestBody Message message) {
        return ApiResult.ok(Map.of("id",service.send(CurrentUser.requireId(),id,message.templateCode(),message.requestId())));
    }
}
