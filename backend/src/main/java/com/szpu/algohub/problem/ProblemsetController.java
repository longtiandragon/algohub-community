package com.szpu.algohub.problem;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.problem.ProblemsetService.Query;

/** 题库浏览：按平台、难度、算法方向、通过状态筛选已同步的题目。 */
@RestController
@RequestMapping("/api/problems")
public class ProblemsetController {

    private final ProblemsetService problemsetService;

    public ProblemsetController(ProblemsetService problemsetService) {
        this.problemsetService = problemsetService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> search(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer minDifficulty,
            @RequestParam(required = false) Integer maxDifficulty,
            @RequestParam(defaultValue = "false") boolean onlyRated,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(problemsetService.search(CurrentUser.requireId(),
                new Query(platform, minDifficulty, maxDifficulty, onlyRated, keyword, category,
                        status, sort, page, size)));
    }
}
