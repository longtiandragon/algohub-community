package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.TimeSupport;

@RestController
@RequestMapping("/api/stats")
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final AuthService authService;
    private final CampusRankingService campusRankingService;

    public StatisticsController(StatisticsService statisticsService, AuthService authService,
                                CampusRankingService campusRankingService) {
        this.statisticsService = statisticsService;
        this.authService = authService;
        this.campusRankingService = campusRankingService;
    }

    /**
     * 看别人的数据一律先过档案可见性：与「个人档案」用同一条规则，
     * 免得档案接口拦住了、统计接口却把同样的数据原样交出去。
     */
    private void requireVisible(Long targetUserId) {
        Long viewerId = CurrentUser.requireId();
        if (targetUserId.equals(viewerId)) {
            return;
        }
        // 不可见与不存在统一 404（与 /api/me/profile/{id} 一致）
        authService.requireReadableProfile(targetUserId, viewerId, authService.canReadPrivateProfiles(viewerId));
    }

    @GetMapping("/profile")
    public ApiResult<Map<String, Object>> myProfile() {
        return ApiResult.ok(statisticsService.profile(CurrentUser.requireId()));
    }

    @GetMapping("/snapshots")
    public ApiResult<List<Map<String, Object>>> mySnapshots() {
        return ApiResult.ok(statisticsService.snapshots(CurrentUser.requireId()));
    }

    @GetMapping("/profile/{userId}")
    public ApiResult<Map<String, Object>> profile(@PathVariable Long userId) {
        requireVisible(userId);
        if (userId.equals(CurrentUser.requireId()) || authService.canReadPrivateProfiles(CurrentUser.requireId())) {
            return ApiResult.ok(statisticsService.profile(userId));
        }
        // 看别人：只给已验证归属的账号；对方关了「展示平台账号」就不给账号名
        return ApiResult.ok(statisticsService.profileForOthers(userId, false));
    }

    @GetMapping("/heatmap")
    public ApiResult<Map<String, Object>> heatmap(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String platform) {
        LocalDate end = to != null ? to : TimeSupport.today();
        LocalDate start = from != null ? from : end.minusDays(364);
        Long target = userId != null ? userId : CurrentUser.requireId();
        requireVisible(target);
        return ApiResult.ok(statisticsService.heatmap(target, start, end,
                platform == null || platform.isBlank() ? null : platform,
                !target.equals(CurrentUser.requireId()) && !authService.canReadPrivateProfiles(CurrentUser.requireId())));
    }

    /**
     * 排行榜。type: TOTAL / WEEK / MONTH / YEAR（近一年）；metric 仅对区间榜有效。
     */
    @GetMapping("/ranking")
    public ApiResult<Map<String, Object>> ranking(
            @RequestParam(defaultValue = "SCHOOL") String scope,
            @RequestParam(defaultValue = "TOTAL") String type,
            @RequestParam(defaultValue = "FIRST_AC") String metric,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String className,
            @RequestParam(defaultValue = "50") int limit) {
        // 校内榜的学校由服务端成员记录确定；总榜不要求邀请码或学校成员身份。
        return ApiResult.ok(campusRankingService.ranking(CurrentUser.requireId(), scope.toUpperCase(), type.toUpperCase(), metric.toUpperCase(),
                platform == null || platform.isBlank() ? null : platform,
                year, className == null || className.isBlank() ? null : className.trim(), limit));
    }
}
