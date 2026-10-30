package com.szpu.algohub.account;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.dao.PessimisticLockingFailureException;

import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.ApiException;

/** 仅接受短时、单账号同步码；浏览器脚本不需要取得 AlgoHub 登录凭据。 */
@RestController
@RequestMapping("/api/qoj/browser-import")
public class QojBrowserImportController {
    private final QojBrowserImportService service;

    public QojBrowserImportController(QojBrowserImportService service) { this.service = service; }

    @PostMapping("/page")
    public ApiResult<QojBrowserImportService.Progress> page(
            @RequestHeader("X-AlgoHub-Import-Ticket") String ticket,
            @RequestBody QojBrowserImportService.Page page) {
        // 重试必须在事务代理外：失败调用先完整回滚，再用相同分页开启新事务。
        // 不重试验证/授权失败，成功响应后也不重复提交。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return ApiResult.ok(service.pageWithTicket(ticket, page));
            } catch (PessimisticLockingFailureException conflict) {
                if (attempt == 2) {
                    throw ApiException.conflict("同步写入暂时繁忙，请稍后重新同步");
                }
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ApiException.conflict("同步请求已中断，请稍后重新同步");
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    @PostMapping("/auto-begin")
    public ApiResult<java.util.Map<String, String>> autoBegin(
            @RequestHeader("X-AlgoHub-Auto-Token") String token) {
        return ApiResult.ok(service.autoBegin(token));
    }
}
