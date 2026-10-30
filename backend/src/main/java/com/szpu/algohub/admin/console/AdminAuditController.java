package com.szpu.algohub.admin.console;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;

/** 操作日志查询：按时间倒序，可按操作人、关键词（接口模板和对象）筛选。 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private final AdminAuditLog audit;

    public AdminAuditController(AdminAuditLog audit) {
        this.audit = audit;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam(defaultValue = "0") long actorId,
                                               @RequestParam(defaultValue = "") String q,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100 || q.length() > 128 || actorId < 0) {
            throw ApiException.validation("筛选或分页参数不正确");
        }
        return ApiResult.ok(audit.page(actorId, q.trim(), page, size));
    }
}
