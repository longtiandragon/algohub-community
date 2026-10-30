package com.szpu.algohub.bookmark;

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
import com.szpu.algohub.bookmark.BookmarkService.SaveRequest;
import com.szpu.algohub.common.ApiResult;

/**
 * 题目收藏与复习记录。只读写当前登录用户自己的数据，不接受目标用户 ID：
 * 笔记默认仅本人可见。
 */
@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String platform,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(defaultValue = "false") boolean dueOnly,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(bookmarkService.list(CurrentUser.requireId(), status, platform, category,
                dueOnly, page, size));
    }

    /** 新增或更新收藏。同一题重复提交是幂等的，只会更新那一条。 */
    @PostMapping
    public ApiResult<Map<String, Object>> save(@RequestBody SaveRequest request) {
        return ApiResult.ok(bookmarkService.save(CurrentUser.requireId(), request));
    }

    @DeleteMapping("/{problemId}")
    public ApiResult<Map<String, Object>> remove(@PathVariable Long problemId) {
        return ApiResult.ok(bookmarkService.remove(CurrentUser.requireId(), problemId));
    }

    /** 一批题目的收藏状态，供题目列表一次标出，不用每题请求一次。 */
    @GetMapping("/status")
    public ApiResult<Map<Long, String>> statusOf(@RequestParam List<Long> problemIds) {
        return ApiResult.ok(bookmarkService.statusOf(CurrentUser.requireId(), problemIds));
    }
}
