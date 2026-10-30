package com.szpu.algohub.activity;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.activity.ActivityService.FeedPage;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.AuthService;
import org.springframework.web.bind.annotation.PathVariable;
import com.szpu.algohub.common.ApiResult;


/** 校内刷题动态、成就与每日一题。 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;
    private final AchievementService achievementService;
    private final AuthService authService;

    public ActivityController(ActivityService activityService, AchievementService achievementService, AuthService authService) {
        this.activityService = activityService;
        this.achievementService = achievementService;
        this.authService = authService;
    }

    /**
     * 校内刷题动态。游标翻页：把上一页返回的 nextCursor 原样传回来。
     * year 是入学年级（「同届」筛选），不传就是全校。
     * 个人提交记录（含未通过、可筛选导出）在 /api/history，不在这里重复提供。
     */
    @GetMapping("/feed")
    public ApiResult<FeedPage> feed(@RequestParam(required = false) String platform,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) Integer year,
                                    @RequestParam(defaultValue = "SCHOOL") String scope,
                                    @RequestParam(required = false) java.util.List<Integer> years,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "false") boolean followingOnly,
                                    @RequestParam(defaultValue = "30") int limit) {
        return ApiResult.ok(activityService.feed(CurrentUser.requireId(), platform, userId, scope, years != null ? years : year == null ? null : java.util.List.of(year), cursor, limit, followingOnly));
    }

    /** 近 7 天校内（或 year 那一届）在练人数与通过题数。 */
    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary(@RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "SCHOOL") String scope,
            @RequestParam(required = false) java.util.List<Integer> years) {
        return ApiResult.ok(activityService.feedSummary(CurrentUser.requireId(), scope, years != null ? years : year == null ? null : java.util.List.of(year)));
    }
    @GetMapping("/coverage")
    public ApiResult<java.util.List<Map<String,Object>>> coverage() {
        return ApiResult.ok(activityService.ownCoverage(CurrentUser.requireId()));
    }

    /** 成就与里程碑：全部由已同步数据实时推导，不单独保存解锁记录。 */
    @GetMapping("/achievements")
    public ApiResult<Map<String, Object>> achievements() {
        return ApiResult.ok(achievementService.achievements(CurrentUser.requireId()));
    }

    @GetMapping("/achievements/{userId}")
    public ApiResult<Map<String, Object>> publicAchievements(@PathVariable Long userId) {
        boolean privateProfiles = authService.canReadPrivateProfiles(CurrentUser.requireId());
        authService.requireReadableProfile(userId, CurrentUser.requireId(), privateProfiles);
        return ApiResult.ok(achievementService.achievements(userId, !userId.equals(CurrentUser.requireId())
                && !privateProfiles));
    }

    /** 每日一题：按日期从已同步题库确定性选题，同一天全校相同。 */
    @GetMapping("/daily-problem")
    public ApiResult<Map<String, Object>> dailyProblem(
            @RequestParam(defaultValue = "CODEFORCES") String platform) {
        return ApiResult.ok(achievementService.dailyProblem(CurrentUser.requireId(), platform));
    }

    /** 连续刷题天数等激励指标。 */
    @GetMapping("/streak")
    public ApiResult<Map<String, Object>> streak() {
        return ApiResult.ok(activityService.streak(CurrentUser.requireId()));
    }

    @GetMapping("/streak/{userId}")
    public ApiResult<Map<String, Object>> publicStreak(@PathVariable Long userId) {
        boolean privateProfiles = authService.canReadPrivateProfiles(CurrentUser.requireId());
        authService.requireReadableProfile(userId, CurrentUser.requireId(), privateProfiles);
        return ApiResult.ok(activityService.streak(userId, !userId.equals(CurrentUser.requireId())
                && !privateProfiles));
    }
}
