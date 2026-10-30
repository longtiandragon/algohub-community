package com.szpu.algohub.account;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/**
 * 码蹄集按昵称找账号。不带 start 时只查共享索引（立即返回，不请求外站）；
 * 带 start 时实时翻排行榜的一页，翻到的整页同时写进索引。
 */
@RestController
@RequestMapping("/api/accounts/matiji-search")
public class MatijiSearchController {

    private final MatijiIndexService indexService;

    public MatijiSearchController(MatijiIndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> search(@RequestParam String nickname,
                                                 @RequestParam(required = false) Integer start) {
        Long userId = CurrentUser.requireId();
        return ApiResult.ok(start == null ? indexService.lookup(nickname) : indexService.scan(userId, nickname, start));
    }
}
