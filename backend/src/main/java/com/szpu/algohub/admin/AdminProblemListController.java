package com.szpu.algohub.admin;

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
import com.szpu.algohub.common.Limits;
import com.szpu.algohub.problem.ProblemList;
import com.szpu.algohub.problem.ProblemListService;
import com.szpu.algohub.problem.ProblemListService.CreateListRequest;
import com.szpu.algohub.problem.ProblemListService.GenerateRequest;

/** 题单管理：人工建单、按规则从题库生成、增删题目与排序。 */
@RestController
@RequestMapping("/api/admin/problem-lists")
public class AdminProblemListController {

    private final ProblemListService listService;
    private final com.szpu.algohub.problem.ProblemGroupService groupService;

    public AdminProblemListController(ProblemListService listService,
                                      com.szpu.algohub.problem.ProblemGroupService groupService) {
        this.listService = listService;
        this.groupService = groupService;
    }

    public record AddItemsRequest(List<Long> problemIds, String note) {
    }

    public record ReorderRequest(List<Long> problemIds) {
    }

    /** shareScope：PUBLIC 全站 / SCHOOL 指定学校（需 schoolId）；留空保持原样。 */
    public record UpdateRequest(String title, String description, String category,
                                Integer sortOrder, Boolean visible, String shareScope, Long schoolId) {
    }

    /** 管理视角包含隐藏的题单。 */
    @GetMapping
    public ApiResult<List<ProblemList>> list() {
        return ApiResult.ok(listService.listAll(CurrentUser.requireId(), true, "ALL"));
    }

    /** 管理端可查看隐藏、私有题单；由题单模块权限控制。 */
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        Long userId = CurrentUser.requireId();
        listService.requireReadable(id, userId, true);
        return ApiResult.ok(listService.detail(id, userId));
    }

    @PostMapping
    public ApiResult<ProblemList> create(@RequestBody CreateListRequest request) {
        return ApiResult.ok(listService.create(CurrentUser.requireId(), true, request));
    }

    /** 按平台与难度区间从题库生成题单，规则会记录在题单里。 */
    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody GenerateRequest request) {
        return ApiResult.ok(listService.generate(CurrentUser.requireId(), true, request));
    }

    @PostMapping("/{id}")
    public ApiResult<ProblemList> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return ApiResult.ok(listService.update(id,
                new CreateListRequest(null, request.title(), request.description(),
                        request.category(), null, request.sortOrder(), request.shareScope(), request.schoolId()),
                request.visible()));
    }

    @PostMapping("/{id}/items")
    public ApiResult<Map<String, Object>> addItems(@PathVariable Long id,
                                                   @RequestBody AddItemsRequest request) {
        return ApiResult.ok(listService.addItems(id, request.problemIds(), request.note()));
    }

    @DeleteMapping("/{id}/items/{problemId}")
    public ApiResult<Void> removeItem(@PathVariable Long id, @PathVariable Long problemId) {
        listService.removeItem(id, problemId);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/reorder")
    public ApiResult<Void> reorder(@PathVariable Long id, @RequestBody ReorderRequest request) {
        listService.reorder(id, request.problemIds());
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        listService.delete(id);
        return ApiResult.ok();
    }

    /** 复用题目搜索，方便挑题加入题单。 */
    @GetMapping("/problems")
    public ApiResult<List<com.szpu.algohub.problem.ProblemGroupDtos.ProblemSearchItem>> searchProblems(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(groupService.searchProblems(platform, keyword, Limits.clamp(limit, 200)));
    }
}
