package com.szpu.algohub.statistics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiResult;

/** 比赛与 Rating：只给本人自己的账号，按账号分组、按时间先后。 */
@RestController
@RequestMapping("/api/stats")
public class RatingHistoryController {

    private final ContestParticipationMapper mapper;
    private final AuthService authService;

    public RatingHistoryController(ContestParticipationMapper mapper, AuthService authService) {
        this.mapper = mapper;
        this.authService = authService;
    }

    @GetMapping("/rating-history")
    public ApiResult<List<Map<String, Object>>> mine() {
        return history(CurrentUser.requireId(), false, false);
    }

    @GetMapping("/rating-history/{userId}")
    public ApiResult<List<Map<String, Object>>> publicHistory(@PathVariable Long userId) {
        Long viewerId = CurrentUser.requireId();
        authService.requireReadableProfile(userId, viewerId, authService.canReadPrivateProfiles(viewerId));
        return history(userId, true, !authService.privacy(userId).isShowAccounts());
    }

    private ApiResult<List<Map<String, Object>>> history(Long userId, boolean verifiedOnly, boolean hideHandle) {
        Map<Long, Map<String, Object>> series = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> contests = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.historyByUser(userId)) {
            if (verifiedOnly && !"VERIFIED".equals(row.get("ownershipStatus"))) continue;
            Long accountId = ((Number) row.get("accountId")).longValue();
            series.computeIfAbsent(accountId, id -> {
                Map<String, Object> head = new LinkedHashMap<>();
                head.put("accountId", id);
                head.put("platform", row.get("platform"));
                head.put("handle", hideHandle ? "已隐藏" : row.get("handle"));
                return head;
            });
            Map<String, Object> contest = new LinkedHashMap<>();
            contest.put("contestKey", row.get("contestKey"));
            contest.put("contestName", row.get("contestName"));
            contest.put("place", row.get("place"));
            contest.put("oldRating", row.get("oldRating"));
            contest.put("newRating", row.get("newRating"));
            contest.put("performance", row.get("performance"));
            contest.put("rated", row.get("rated"));
            contest.put("finishedAt", instant(row.get("finishedAt")));
            contests.computeIfAbsent(accountId, id -> new ArrayList<>()).add(contest);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        series.forEach((accountId, head) -> {
            head.put("contests", contests.get(accountId));
            result.add(head);
        });
        return ApiResult.ok(result);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) value;
    }
}
