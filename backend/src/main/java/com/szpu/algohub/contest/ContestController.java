package com.szpu.algohub.contest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.Limits;

@RestController
@RequestMapping("/api/contests")
public class ContestController {

    private final ContestService contestService;

    public ContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    /** 时间以 UTC 返回，前端按北京时间展示并计算倒计时。 */
    @GetMapping
    public ApiResult<Map<String, Object>> upcoming(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean favoritedOnly,
            @RequestParam(defaultValue = "60") int limit) {
        Long userId = CurrentUser.requireId();
        Set<Long> favorited = contestService.favoritedIds(userId);
        List<Contest> contests = favoritedOnly
                ? contestService.favorited(userId)
                : contestService.upcoming(emptyToNull(platform), emptyToNull(category),
                        Limits.clamp(limit, 200));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("contests", contests.stream().map(c -> withFavorite(c, favorited)).toList());
        result.put("categories", contestService.categories());
        result.put("favoritedCount", favorited.size());
        // 从未刷新成功时为 null，前端据此提示“尚未同步”。
        result.put("updatedAt", contestService.lastUpdatedAt());
        return ApiResult.ok(result);
    }

    /** 比赛行本身不存收藏状态，这里按当前用户的收藏集合逐条补一个标记。 */
    private Map<String, Object> withFavorite(Contest contest, Set<Long> favorited) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", contest.getId());
        row.put("platform", contest.getPlatform());
        row.put("contestKey", contest.getContestKey());
        row.put("title", contest.getTitle());
        row.put("startAt", contest.getStartAt());
        row.put("endAt", contest.getEndAt());
        row.put("durationSec", contest.getDurationSec());
        row.put("status", contest.getStatus());
        row.put("category", contest.getCategory());
        row.put("url", contest.getUrl());
        row.put("source", contest.getSource());
        row.put("favorited", favorited.contains(contest.getId()));
        return row;
    }

    @PostMapping("/{id}/favorite")
    public ApiResult<Map<String, Object>> favorite(@PathVariable Long id) {
        return ApiResult.ok(contestService.favorite(CurrentUser.requireId(), id));
    }

    @DeleteMapping("/{id}/favorite")
    public ApiResult<Map<String, Object>> unfavorite(@PathVariable Long id) {
        return ApiResult.ok(contestService.unfavorite(CurrentUser.requireId(), id));
    }

    /**
     * 导出收藏的比赛为 .ics 日历文件。
     *
     * 导出的是当次快照：源站之后改期或取消，已经导入日历的日程不会自动更新，需要重新导出。
     * 访问令牌只保存在内存里，所以前端必须用带 Authorization 头的请求取这个文件，
     * 不能直接用 <a href> 下载。
     */
    @GetMapping("/calendar.ics")
    public ResponseEntity<byte[]> calendar() {
        byte[] body = contestService.calendar(CurrentUser.requireId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("algohub-contests.ics", StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .body(body);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
