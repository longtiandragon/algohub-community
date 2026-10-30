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
import com.szpu.algohub.problem.ProblemGroup;
import com.szpu.algohub.problem.ProblemGroupDtos.AddMemberRequest;
import com.szpu.algohub.problem.ProblemGroupDtos.CreateGroupRequest;
import com.szpu.algohub.problem.ProblemGroupDtos.GroupView;
import com.szpu.algohub.problem.ProblemGroupDtos.ProblemSearchItem;
import com.szpu.algohub.problem.ProblemGroupService;

import jakarta.validation.Valid;

/**
 * 跨平台题目映射的管理接口。映射只能人工建立，不提供按题名自动匹配。
 */
@RestController
@RequestMapping("/api/admin/problem-groups")
public class AdminProblemGroupController {

    private final ProblemGroupService groupService;

    public AdminProblemGroupController(ProblemGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public ApiResult<List<ProblemGroup>> list(@RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(groupService.listGroups(Limits.clamp(limit, 500)));
    }

    @GetMapping("/coverage")
    public ApiResult<Map<String, Object>> coverage() {
        return ApiResult.ok(groupService.coverage());
    }

    @GetMapping("/{id}")
    public ApiResult<GroupView> get(@PathVariable Long id) {
        return ApiResult.ok(groupService.getGroup(id));
    }

    @PostMapping
    public ApiResult<GroupView> create(@Valid @RequestBody CreateGroupRequest request) {
        return ApiResult.ok(groupService.create(CurrentUser.requireId(), request));
    }

    @PostMapping("/{id}/members")
    public ApiResult<GroupView> addMember(@PathVariable Long id, @Valid @RequestBody AddMemberRequest request) {
        return ApiResult.ok(groupService.addMember(CurrentUser.requireId(), id,
                request.problemId(), request.relation()));
    }

    @DeleteMapping("/members/{problemId}")
    public ApiResult<Void> removeMember(@PathVariable Long problemId) {
        groupService.removeMember(problemId);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ApiResult.ok();
    }

    /** 题目搜索：按平台与关键词（题号或标题）查找，供建立映射时选择。 */
    @GetMapping("/problems")
    public ApiResult<List<ProblemSearchItem>> searchProblems(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(groupService.searchProblems(platform, keyword, Limits.clamp(limit, 200)));
    }
}
