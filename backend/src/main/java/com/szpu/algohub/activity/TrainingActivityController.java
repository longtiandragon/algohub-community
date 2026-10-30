package com.szpu.algohub.activity;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.activity.TrainingActivityService.SaveRequest;
import com.szpu.algohub.admin.console.AdminAccess;
import com.szpu.algohub.admin.console.AdminPermission;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

/**
 * 校内训练活动。
 *
 * 读取对所有登录同学开放（未发布的草稿只有管理员看得到）；
 * 增删改走 /api/admin/activities，由安全配置限定管理员。
 */
@RestController
public class TrainingActivityController {

    private final TrainingActivityService activityService;
    private final AdminAccess adminAccess;

    public TrainingActivityController(TrainingActivityService activityService, AdminAccess adminAccess) {
        this.activityService = activityService;
        this.adminAccess = adminAccess;
    }

    private boolean canReadDrafts() {
        AdminAccess.Grant grant = adminAccess.of(CurrentUser.requireId());
        return grant.active() && grant.has(AdminPermission.PROBLEMS);
    }

    @GetMapping("/api/activities")
    public ApiResult<Map<String, Object>> list() {
        return ApiResult.ok(activityService.list(CurrentUser.requireId(), canReadDrafts()));
    }

    @GetMapping("/api/activities/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResult.ok(activityService.detail(CurrentUser.requireId(), id, canReadDrafts()));
    }

    @PostMapping("/api/admin/activities")
    public ApiResult<Map<String, Object>> create(@RequestBody SaveRequest request) {
        return ApiResult.ok(activityService.create(CurrentUser.requireId(), request));
    }

    @PostMapping("/api/admin/activities/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable Long id, @RequestBody SaveRequest request) {
        return ApiResult.ok(activityService.update(id, request));
    }

    @DeleteMapping("/api/admin/activities/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResult.ok(activityService.delete(id));
    }
}
