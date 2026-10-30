package com.szpu.algohub.sync;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.Limits;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncTaskMapper taskMapper;

    public SyncController(SyncTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /** 自己的同步任务状态：排队、同步中、完成或失败。 */
    @GetMapping("/tasks")
    public ApiResult<List<SyncTask>> myTasks(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(taskMapper.recentForUser(CurrentUser.requireId(), Limits.clamp(limit, 100)));
    }
}
