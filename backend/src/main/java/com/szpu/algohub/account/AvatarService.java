package com.szpu.algohub.account;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Avatars;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformRegistry;

/**
 * 头像：不做上传，从本人已验证的 OJ 账号里选一个，用那个平台的头像。
 *
 * 头像地址存在 platform_account.avatar_url，跟着账号同步刷新（距上次检查超过 24 小时才再查一次公开资料），
 * 不在打开页面时去各 OJ 查。app_user.avatar_url 由数据库触发器按所选账号维护（见 V35），这里不直接写。
 * 头像取不到不影响同步和绑定：失败只记日志，保留上次的结果。
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    /** 两次查公开资料取头像之间至少隔这么久。 */
    static final int REFRESH_HOURS = 24;

    public static final String REASON_UNSUPPORTED = "这个平台不提供头像";
    public static final String REASON_UNVERIFIED = "还没验证归属，验证后才能用它的头像";
    public static final String REASON_UNCHECKED = "还没取到头像，同步一次后再来看";
    /** 查过公开资料，平台没给这个账号的头像地址 */
    public static final String REASON_NONE = "平台上没有取到这个账号的头像";

    /** 一个可选的头像来源。selectable 为 false 时 reason 说明为什么不能选。 */
    public record AvatarOption(Long accountId, String platform, String handle, String avatarUrl,
                               boolean selectable, String reason, Instant checkedAt) {
    }

    /** selectedAccountId：本人选的账号（空 = 默认字标）；avatarUrl：现在实际显示的头像（选的账号取不到头像时为空）。 */
    public record AvatarView(Long selectedAccountId, String avatarUrl, List<AvatarOption> options) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformRegistry registry;

    public AvatarService(JdbcTemplate jdbc, PlatformRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public AvatarView view(Long userId) {
        List<Map<String, Object>> users = jdbc.queryForList(
                "select avatar_account_id, avatar_url from app_user where id = ?", userId);
        if (users.isEmpty()) {
            throw ApiException.notFound("用户不存在");
        }
        List<AvatarOption> options = jdbc.queryForList("""
                select id, platform, handle, ownership_status, avatar_url, avatar_checked_at
                  from platform_account where user_id = ? order by platform, id
                """, userId).stream().map(this::option).toList();
        Map<String, Object> user = users.getFirst();
        Number selected = (Number) user.get("avatar_account_id");
        return new AvatarView(selected == null ? null : selected.longValue(), (String) user.get("avatar_url"), options);
    }

    private AvatarOption option(Map<String, Object> row) {
        String platform = (String) row.get("platform");
        String url = (String) row.get("avatar_url");
        Instant checkedAt = BindingRequestService.instant(row.get("avatar_checked_at"));
        boolean supported = adapterOf(platform).map(PlatformAdapter::avatarSupported).orElse(false);
        String reason = !supported ? REASON_UNSUPPORTED
                : !"VERIFIED".equals(row.get("ownership_status")) ? REASON_UNVERIFIED
                : url != null ? null
                : checkedAt == null ? REASON_UNCHECKED
                : REASON_NONE;
        return new AvatarOption(((Number) row.get("id")).longValue(), platform, (String) row.get("handle"),
                supported ? url : null, reason == null, reason, checkedAt);
    }

    /** 选一个账号的头像；accountId 为空时改回默认字标。只能选自己的、已验证、有头像的账号。 */
    public AvatarView select(Long userId, Long accountId) {
        if (accountId == null) {
            jdbc.update("update app_user set avatar_account_id = null, updated_at = now() where id = ?", userId);
            return view(userId);
        }
        AvatarOption option = view(userId).options().stream()
                .filter(o -> o.accountId().equals(accountId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("没有找到你绑定的这个账号"));
        if (!option.selectable()) {
            throw ApiException.validation(option.reason());
        }
        // 条件写进更新语句：查完到写入之间账号被解绑、转移或头像变成默认图，都不会选上
        int updated = jdbc.update("""
                update app_user set avatar_account_id = ?, updated_at = now()
                 where id = ? and exists (select 1 from platform_account a
                                           where a.id = ? and a.user_id = ? and a.ownership_status = 'VERIFIED'
                                             and a.avatar_url is not null)
                """, accountId, userId, accountId, userId);
        if (updated != 1) {
            throw ApiException.conflict("这个账号的头像刚刚变了，请刷新后再选");
        }
        return view(userId);
    }

    /** 绑定 / 验证归属成功时，用已经拿到的公开资料顺手存一次头像。失败只记日志。 */
    public void record(Long accountId, String platformUid, PlatformAdapter adapter, ResolvedIdentity identity) {
        if (adapter == null || !adapter.avatarSupported()) {
            return;
        }
        try {
            save(accountId, platformUid, adapter.avatarOf(identity));
        } catch (RuntimeException e) {
            log.warn("账号 {} 绑定时没取到头像，等下次同步再取：{}", accountId, e.getMessage());
        }
    }

    /**
     * 账号同步成功后调用：已验证、平台有头像、距上次检查超过 24 小时才重新查一次公开资料。
     * 头像变成默认图时清空；失败不影响同步结果，只记日志，下次同步再试。
     *
     * @return 这次是否查过（测试用）
     */
    public boolean refreshIfDue(Long accountId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select platform, platform_uid, handle
                      from platform_account
                     where id = ? and ownership_status = 'VERIFIED'
                       and (avatar_checked_at is null
                            or avatar_checked_at <= clock_timestamp() - (? * interval '1 hour'))
                    """, accountId, REFRESH_HOURS);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> row = rows.getFirst();
            PlatformAdapter adapter = adapterOf((String) row.get("platform")).orElse(null);
            if (adapter == null || !adapter.avatarSupported()) {
                return false;
            }
            String uid = (String) row.get("platform_uid");
            ResolvedIdentity identity = adapter.resolveIdentity(adapter.lookupKey(uid, (String) row.get("handle")), null);
            if (identity == null || (uid != null && !uid.equals(identity.platformUid()))) {
                log.warn("账号 {} 刷新头像时平台返回的 UID 对不上，这次不更新", accountId);
                return true;
            }
            save(accountId, uid, adapter.avatarOf(identity));
            return true;
        } catch (RuntimeException e) {
            log.warn("账号 {} 头像刷新失败（不影响同步）：{}", accountId, e.getMessage());
            return true;
        }
    }

    /** 已删除的平台（库里还留着旧记录）没有适配器，当作不提供头像。 */
    private Optional<PlatformAdapter> adapterOf(String platform) {
        return Arrays.stream(Platform.values()).filter(p -> p.name().equals(platform)).findFirst()
                .flatMap(registry::find);
    }

    /** 只在账号标识没变时写入：查资料期间换了 UID 的结果作废。 */
    private void save(Long accountId, String platformUid, String url) {
        jdbc.update("""
                update platform_account set avatar_url = ?, avatar_checked_at = clock_timestamp()
                 where id = ? and platform_uid is not distinct from ?
                """, Avatars.https(url), accountId, platformUid);
    }
}
