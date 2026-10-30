package com.szpu.algohub.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.AccountService;
import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.Limits;
import com.szpu.algohub.contest.ContestService;
import com.szpu.algohub.resource.NavResource;
import com.szpu.algohub.resource.NavResourceMapper;
import com.szpu.algohub.school.SchoolController;
import com.szpu.algohub.school.SchoolMember;
import com.szpu.algohub.school.SchoolService;
import com.szpu.algohub.sync.PlatformThrottleMapper;
import com.szpu.algohub.sync.SyncService;
import com.szpu.algohub.sync.SyncTask;
import com.szpu.algohub.sync.SyncTaskMapper;

/**
 * 管理后台：成员审批、导航维护、同步状态与失败重试。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SchoolService schoolService;
    private final NavResourceMapper navMapper;
    private final SyncTaskMapper taskMapper;
    private final PlatformAccountMapper accountMapper;
    private final SyncService syncService;
    private final ContestService contestService;
    private final PlatformThrottleMapper throttleMapper;
    private final AccountService accountService;

    public AdminController(SchoolService schoolService, NavResourceMapper navMapper,
                           SyncTaskMapper taskMapper, PlatformAccountMapper accountMapper,
                           SyncService syncService, ContestService contestService,
                           PlatformThrottleMapper throttleMapper, AccountService accountService) {
        this.schoolService = schoolService;
        this.navMapper = navMapper;
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.syncService = syncService;
        this.contestService = contestService;
        this.throttleMapper = throttleMapper;
        this.accountService = accountService;
    }

    public record ReviewRequest(boolean approve, String reason) {
    }

    @GetMapping("/members")
    public ApiResult<List<SchoolMember>> members(@RequestParam(required = false) String status) {
        Long schoolId = schoolService.defaultSchool().getId();
        return ApiResult.ok(schoolService.listMembers(schoolId,
                status == null || status.isBlank() ? null : status));
    }

    @PostMapping("/members/{id}/review")
    public ApiResult<SchoolMember> review(@PathVariable Long id, @RequestBody ReviewRequest request) {
        return ApiResult.ok(schoolService.review(id, request.approve(), CurrentUser.requireId(), request.reason()));
    }

    /** 管理员改成员的年级、班级、学号 */
    @PostMapping("/members/{id}/profile")
    public ApiResult<SchoolMember> updateMember(@PathVariable Long id,
                                                @Valid @RequestBody SchoolController.ProfileRequest request) {
        return ApiResult.ok(schoolService.adminUpdateProfile(id, request.enrollmentYear(), request.className(),
                request.studentNo(), CurrentUser.requireId()));
    }

    @GetMapping("/nav")
    public ApiResult<List<NavResource>> navList() {
        return ApiResult.ok(navMapper.findAll(true));
    }

    @PostMapping("/nav")
    public ApiResult<NavResource> navCreate(@RequestBody NavResource resource) {
        requireWebUrl(resource.getUrl());
        navMapper.insert(resource);
        return ApiResult.ok(resource);
    }

    @PostMapping("/nav/{id}")
    public ApiResult<NavResource> navUpdate(@PathVariable Long id, @RequestBody NavResource resource) {
        requireWebUrl(resource.getUrl());
        resource.setId(id);
        navMapper.update(resource);
        return ApiResult.ok(resource);
    }

    /** 导航链接会直接放进页面的 href：只接受 http / https，挡住 javascript:、data: 之类。 */
    static void requireWebUrl(String url) {
        java.net.URI uri;
        try {
            uri = url == null ? null : new java.net.URI(url.strip());
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw ApiException.validation("链接地址必须是 http:// 或 https:// 开头的完整网址");
        }
    }

    @DeleteMapping("/nav/{id}")
    public ApiResult<Void> navDelete(@PathVariable Long id) {
        navMapper.delete(id);
        return ApiResult.ok();
    }

    /** 同步概览：最近成功时间、错误分类、待处理任务。 */
    @GetMapping("/sync/overview")
    public ApiResult<Map<String, Object>> syncOverview(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", taskMapper.countByStatus("PENDING"));
        result.put("running", taskMapper.countByStatus("RUNNING"));
        result.put("failed", taskMapper.countByStatus("FAILED"));
        result.put("tasks", taskMapper.recent(Limits.clamp(limit, 200)));
        result.put("accounts", accountMapper.findAll().stream()
                .filter(account -> !"LANQIAO".equals(account.getPlatform())).toList());
        // 正在冷却的平台（刚被限流）：冷却结束前同平台任务暂不执行
        result.put("throttles", throttleMapper.active());
        return ApiResult.ok(result);
    }

    /** 失败重试：重新入队该账号的同步任务。 */
    @PostMapping("/sync/accounts/{accountId}/retry")
    public ApiResult<Void> retry(@PathVariable Long accountId) {
        PlatformAccount account = accountMapper.findById(accountId);
        if (account == null || "LANQIAO".equals(account.getPlatform())) {
            return ApiResult.fail(com.szpu.algohub.common.ErrorKind.NOT_FOUND, "账号不存在");
        }
        syncService.enqueueAccountSync(accountId, account.getPlatform(), 10);
        return ApiResult.ok();
    }

    /** 管理端解绑：沿用同步模块权限检查与操作审计。 */
    @DeleteMapping("/sync/accounts/{accountId}")
    public ApiResult<Void> unbindAccount(@PathVariable Long accountId) {
        accountService.unbind(accountId, CurrentUser.requireId(), true);
        return ApiResult.ok();
    }

    /** 题库同步：拉取平台公开题目清单（含难度与标签），供推荐与标签统计使用。 */
    @PostMapping("/sync/catalog")
    public ApiResult<Void> refreshCatalog(@RequestParam(defaultValue = "CODEFORCES") String platform) {
        syncService.enqueueCatalogRefresh(platform.toUpperCase());
        return ApiResult.ok();
    }

    @PostMapping("/sync/contests/refresh")
    public ApiResult<Void> refreshContests() {
        syncService.enqueueContestRefresh();
        return ApiResult.ok();
    }

    /** 重算某账号的日统计，用于规则调整或重判后的纠错。 */
    @PostMapping("/sync/accounts/{accountId}/rebuild")
    public ApiResult<Void> rebuild(@PathVariable Long accountId) {
        syncService.rebuildSubmissionStats(accountId);
        return ApiResult.ok();
    }

    @PostMapping("/contests/{id}/category")
    public ApiResult<Void> updateCategory(@PathVariable Long id, @RequestParam String category) {
        contestService.updateCategory(id, category);
        return ApiResult.ok();
    }

    @GetMapping("/tasks")
    public ApiResult<List<SyncTask>> tasks(@RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(taskMapper.recent(Limits.clamp(limit, 200)));
    }
}
