package com.szpu.algohub.goal;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.goal.WeeklyGoalService.SaveRequest;

/** 个人周目标。只读写自己的目标，进度由自己的通过记录实时算出。 */
@RestController
@RequestMapping("/api/goals/weekly")
public class WeeklyGoalController {

    private final WeeklyGoalService weeklyGoalService;

    public WeeklyGoalController(WeeklyGoalService weeklyGoalService) {
        this.weeklyGoalService = weeklyGoalService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> current() {
        return ApiResult.ok(weeklyGoalService.current(CurrentUser.requireId()));
    }

    /** 设定或修改本周目标。同一周重复提交只会更新那一条。 */
    @PostMapping
    public ApiResult<Map<String, Object>> save(@RequestBody SaveRequest request) {
        return ApiResult.ok(weeklyGoalService.save(CurrentUser.requireId(), request));
    }

    @DeleteMapping
    public ApiResult<Map<String, Object>> remove() {
        return ApiResult.ok(weeklyGoalService.remove(CurrentUser.requireId()));
    }
}
