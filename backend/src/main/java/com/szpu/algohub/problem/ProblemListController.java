package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.problem.ProblemListService.CreateListRequest;
import com.szpu.algohub.problem.ProblemListService.GenerateRequest;

/**
 * 题单：浏览、自建与收藏。
 *
 * 学生可以自己建训练计划（默认仅自己可见，可改为校内共享），
 * 也可以收藏别人分享的题单；系统题单仍只有管理员能维护。
 * 普通接口始终按本人权限处理，管理员维护须走 /api/admin/problem-lists。
 */
@RestController
@RequestMapping("/api/problem-lists")
public class ProblemListController {

    private final ProblemListService listService;

    public ProblemListController(ProblemListService listService) {
        this.listService = listService;
    }

    public record AddItemsRequest(List<Long> problemIds, String note) {
    }

    public record ReorderRequest(List<Long> problemIds) {
    }

    public record UpdateRequest(String title, String description, String category,
                                Integer sortOrder, String shareScope) {
    }

    /**
     * @param scope ALL（默认）/ MINE 我建的 / FAVORITE 我收藏的 / SYSTEM 系统题单
     */
    @GetMapping
    public ApiResult<List<ProblemList>> list(@RequestParam(defaultValue = "ALL") String scope) {
        return ApiResult.ok(listService.listAll(CurrentUser.requireId(), false, scope));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        Long userId = CurrentUser.requireId();
        listService.requireReadable(id, userId, false);
        return ApiResult.ok(listService.detail(id, userId));
    }

    /** 题单的校内完成榜：只列本校同学，口径与排行榜一致（尊重隐私设置）。 */
    @GetMapping("/{id}/leaderboard")
    public ApiResult<Map<String, Object>> leaderboard(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "20") int limit) {
        Long userId = CurrentUser.requireId();
        listService.requireReadable(id, userId, false);
        return ApiResult.ok(listService.leaderboard(id, userId, limit));
    }

    // ---------- 自建题单 ----------

    /** 学生自建题单，默认仅自己可见。 */
    @PostMapping
    public ApiResult<ProblemList> create(@RequestBody CreateListRequest request) {
        return ApiResult.ok(listService.create(CurrentUser.requireId(), false, request));
    }

    /** 按规则从题库生成自己的训练计划（可按难度区间与标签分类筛选）。 */
    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody GenerateRequest request) {
        return ApiResult.ok(listService.generate(CurrentUser.requireId(), false, request));
    }

    @PostMapping("/{id}")
    public ApiResult<ProblemList> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        listService.requireEditable(id, CurrentUser.requireId(), false);
        return ApiResult.ok(listService.update(id,
                new CreateListRequest(null, request.title(), request.description(), request.category(),
                        null, request.sortOrder(), request.shareScope()),
                null));
    }

    @PostMapping("/{id}/items")
    public ApiResult<Map<String, Object>> addItems(@PathVariable Long id,
                                                   @RequestBody AddItemsRequest request) {
        listService.requireEditable(id, CurrentUser.requireId(), false);
        return ApiResult.ok(listService.addItems(id, request.problemIds(), request.note()));
    }

    @DeleteMapping("/{id}/items/{problemId}")
    public ApiResult<Void> removeItem(@PathVariable Long id, @PathVariable Long problemId) {
        listService.requireEditable(id, CurrentUser.requireId(), false);
        listService.removeItem(id, problemId);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/reorder")
    public ApiResult<Void> reorder(@PathVariable Long id, @RequestBody ReorderRequest request) {
        listService.requireEditable(id, CurrentUser.requireId(), false);
        listService.reorder(id, request.problemIds());
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        listService.requireEditable(id, CurrentUser.requireId(), false);
        listService.delete(id);
        return ApiResult.ok();
    }

    // ---------- 收藏 ----------

    @PostMapping("/{id}/favorite")
    public ApiResult<Map<String, Object>> favorite(@PathVariable Long id) {
        return ApiResult.ok(listService.setFavorite(CurrentUser.requireId(), id, true, false));
    }

    @DeleteMapping("/{id}/favorite")
    public ApiResult<Map<String, Object>> unfavorite(@PathVariable Long id) {
        return ApiResult.ok(listService.setFavorite(CurrentUser.requireId(), id, false, false));
    }
}
