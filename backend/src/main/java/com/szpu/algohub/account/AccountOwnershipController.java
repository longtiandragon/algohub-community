package com.szpu.algohub.account;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.account.BindingRequestService.VerifyResult;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;

/**
 * 待认领账号（登记时没有证明归属的旧记录）的验证入口。
 * 与新绑定走同一套申请与核验，账号 id、登录态与刷题记录在验证后原样保留。
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/ownership")
public class AccountOwnershipController {

    private final AccountService accounts;
    private final BindingRequestService requests;
    private final JdbcTemplate jdbc;

    public AccountOwnershipController(AccountService accounts, BindingRequestService requests, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.requests = requests;
        this.jdbc = jdbc;
    }

    @PostMapping("/challenge")
    public ApiResult<Map<String, Object>> challenge(@PathVariable Long accountId) {
        PlatformAccount account = accounts.requireOwned(accountId, CurrentUser.requireId(), false);
        return ApiResult.ok(requests.createFor(account.getUserId(), account.getPlatform(),
                account.getPlatformUid(), account.getHandle()));
    }

    @PostMapping("/verify")
    public ApiResult<VerifyResult> verify(@PathVariable Long accountId) {
        PlatformAccount account = accounts.requireOwned(accountId, CurrentUser.requireId(), false);
        List<Long> ids = jdbc.queryForList("""
                select id from binding_request
                 where account_id = ? and user_id = ? and verified_at is null
                 order by id desc
                """, Long.class, accountId, account.getUserId());
        if (ids.isEmpty()) {
            throw ApiException.validation("请先获取验证串");
        }
        return ApiResult.ok(requests.verify(account.getUserId(), ids.getFirst()));
    }
}
