package com.szpu.algohub.account;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.account.AvatarService.AvatarView;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/** 头像：列出本人各 OJ 账号的头像候选，选一个或改回默认字标。 */
@RestController
@RequestMapping("/api/me/avatar")
public class AvatarController {

    /** accountId 为空 = 用默认字标。 */
    public record SelectRequest(Long accountId) {
    }

    private final AvatarService avatars;

    public AvatarController(AvatarService avatars) {
        this.avatars = avatars;
    }

    @GetMapping
    public ApiResult<AvatarView> view() {
        return ApiResult.ok(avatars.view(CurrentUser.requireId()));
    }

    @PostMapping
    public ApiResult<AvatarView> select(@RequestBody SelectRequest request) {
        return ApiResult.ok(avatars.select(CurrentUser.requireId(), request == null ? null : request.accountId()));
    }
}
