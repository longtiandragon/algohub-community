package com.szpu.algohub.account;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.sync.SyncService;

/**
 * 归属申诉。
 *
 * 平台账号已被另一位同学验证时，申请人先用公开资料证明自己当前能控制该账号（见 BindingRequestService），
 * 证明成功才生成申诉。管理员审核通过后只转移"已验证归属"这一个标记：
 * - 原归属者的账号行、刷题记录与登录态原样保留，转为个人关注，并在账号上留下说明；
 * - 申请人已有该 UID 的个人关注记录就原地升级，没有则新建一行、重新同步；
 * - 任何一方的记录或 Cookie 都不会搬给另一方。
 */
@Service
public class OwnershipAppealService {

    private static final int MAX_TEXT = 500;

    private final JdbcTemplate jdbc;
    private final PlatformAccountMapper accounts;
    private final PlatformRegistry registry;
    private final SyncService syncService;
    private final AuthService authService;

    public OwnershipAppealService(JdbcTemplate jdbc, PlatformAccountMapper accounts, PlatformRegistry registry,
                                  SyncService syncService, AuthService authService) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.registry = registry;
        this.syncService = syncService;
        this.authService = authService;
    }

    /**
     * 核验成功但账号已归他人时生成申诉。在调用方的核验事务内执行：
     * 同一申请人对同一账号已有待审核申诉时只刷新证明时间，不重复生成。
     */
    Long openFromProof(Long appellantId, String platform, String uid, String handle,
                       PlatformAccount owner, Long requestId) {
        List<Long> pending = jdbc.queryForList("""
                select id from ownership_appeal
                 where appellant_id = ? and platform = ? and platform_uid = ? and status = 'PENDING'
                """, Long.class, appellantId, platform, uid);
        if (!pending.isEmpty()) {
            jdbc.update("""
                    update ownership_appeal
                       set proved_at = clock_timestamp(), request_id = ?, handle = ?,
                           owner_account_id = ?, owner_user_id = ?
                     where id = ?
                    """, requestId, handle, owner.getId(), owner.getUserId(), pending.getFirst());
            return pending.getFirst();
        }
        Long id = jdbc.queryForObject("""
                insert into ownership_appeal (platform, platform_uid, handle, appellant_id, owner_account_id,
                                              owner_user_id, request_id, proved_at)
                values (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                returning id
                """, Long.class, platform, uid, handle, appellantId, owner.getId(), owner.getUserId(), requestId);
        authService.audit(appellantId, null, "OWNERSHIP_APPEAL_OPENED", null, null,
                Platform.parse(platform).getDisplayName() + " " + handle);
        return id;
    }

    /** 我提交过的申诉。只返回本人的，不含对方身份。 */
    public List<Map<String, Object>> mine(Long userId) {
        return jdbc.queryForList("""
                select id, platform, platform_uid as "platformUid", handle, status, reason,
                       proved_at as "provedAt", created_at as "createdAt",
                       reviewed_at as "reviewedAt", review_note as "reviewNote"
                  from ownership_appeal
                 where appellant_id = ?
                 order by created_at desc, id desc
                 limit 50
                """, userId);
    }

    /** 补充申诉理由，只能改自己仍在待审核的申诉。 */
    public void updateReason(Long userId, Long appealId, String reason) {
        int updated = jdbc.update("""
                update ownership_appeal set reason = ?
                 where id = ? and appellant_id = ? and status = 'PENDING'
                """, text(reason), appealId, userId);
        if (updated != 1) {
            throw ApiException.notFound("没有找到你待审核的这条申诉");
        }
    }

    public void withdraw(Long userId, Long appealId) {
        int updated = jdbc.update("""
                update ownership_appeal set status = 'WITHDRAWN', reviewed_at = now()
                 where id = ? and appellant_id = ? and status = 'PENDING'
                """, appealId, userId);
        if (updated != 1) {
            throw ApiException.notFound("没有找到你待审核的这条申诉");
        }
    }

    /**
     * 管理员视角：申请人、申诉时记录的归属者、现在的归属者及其验证时间并排给出，
     * 由管理员判断，系统不按先来后到自动裁决。
     */
    public List<Map<String, Object>> list(String status) {
        String filter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        return jdbc.queryForList("""
                select a.id, a.platform, a.platform_uid as "platformUid", a.handle, a.status, a.reason,
                       a.proved_at as "provedAt", a.created_at as "createdAt",
                       a.reviewed_at as "reviewedAt", a.review_note as "reviewNote",
                       ap.id as "appellantId", ap.username as "appellantUsername", ap.display_name as "appellantName",
                       ow.id as "recordedOwnerId", ow.username as "recordedOwnerUsername",
                       ow.display_name as "recordedOwnerName",
                       cu.id as "currentOwnerId", cu.username as "currentOwnerUsername",
                       cu.display_name as "currentOwnerName",
                       (select max(r.verified_at) from binding_request r
                         where r.user_id = cur.user_id and r.platform = a.platform
                           and r.platform_uid = a.platform_uid) as "currentOwnerVerifiedAt",
                       rv.username as "reviewerUsername"
                  from ownership_appeal a
                  join app_user ap on ap.id = a.appellant_id
                  left join app_user ow on ow.id = a.owner_user_id
                  left join platform_account cur on cur.platform = a.platform
                       and cur.platform_uid = a.platform_uid and cur.ownership_status = 'VERIFIED'
                  left join app_user cu on cu.id = cur.user_id
                  left join app_user rv on rv.id = a.reviewed_by
                 where (?::varchar is null or a.status = ?::varchar)
                 order by (a.status = 'PENDING') desc, a.created_at desc, a.id desc
                 limit 200
                """, filter, filter);
    }

    /** 通过：撤下当前归属者的标记（记录与凭据保留），把归属交给申请人并排队同步。 */
    @Transactional
    public Map<String, Object> approve(Long adminId, Long appealId, String note) {
        Map<String, Object> appeal = lockPending(appealId);
        String platform = (String) appeal.get("platform");
        String uid = (String) appeal.get("platform_uid");
        String handle = (String) appeal.get("handle");
        Long appellantId = ((Number) appeal.get("appellant_id")).longValue();
        String platformName = Platform.parse(platform).getDisplayName();

        // 按实时状态找归属者：可能已不是申诉时记录的那一位
        PlatformAccount owner = accounts.lockVerifiedOwner(platform, uid);
        if (owner != null && !owner.getUserId().equals(appellantId)) {
            accounts.revokeVerified(owner.getId(), TimeSupport.today()
                    + " 管理员审核申诉后确认该账号由另一位同学控制，已撤下本账号的已验证归属，转为个人关注；"
                    + "你的刷题记录与登录态均保留。如有异议，可重新验证并提交申诉。");
            authService.audit(owner.getUserId(), null, "OWNERSHIP_REVOKED", null, null,
                    platformName + " " + handle + "（申诉 #" + appealId + "）");
        }

        PlatformAccount account = accounts.findMineByUid(appellantId, platform, uid);
        if (account == null) {
            List<Long> sameHandle = jdbc.queryForList("""
                    select id from platform_account where user_id = ? and platform = ? and handle = ?
                    """, Long.class, appellantId, platform, handle);
            if (!sameHandle.isEmpty()) {
                throw ApiException.conflict("申请人已有一条同名但 UID 不同的" + platformName
                        + "记录，请让申请人先解绑旧记录再审核");
            }
            PlatformCapability capability = registry.require(Platform.parse(platform)).capability();
            account = new PlatformAccount();
            account.setUserId(appellantId);
            account.setPlatform(platform);
            account.setPlatformUid(uid);
            account.setHandle(handle);
            account.setOwnershipStatus("VERIFIED");
            account.setAuthMode("PUBLIC");
            account.setSyncStatus("IDLE");
            account.setSupportsHistory(capability.submissionHistory());
            account.setNote(capability.notes());
            accounts.insert(account);
        } else {
            accounts.markVerified(account.getId(), appellantId, handle);
        }
        syncService.enqueueAccountSync(account.getId(), platform, 90);

        jdbc.update("""
                update ownership_appeal
                   set status = 'APPROVED', reviewed_by = ?, reviewed_at = now(), review_note = ?
                 where id = ?
                """, adminId, text(note), appealId);
        authService.audit(appellantId, null, "OWNERSHIP_APPEAL_APPROVED", null, null,
                platformName + " " + handle + "（申诉 #" + appealId + "）");
        return Map.of("appealId", appealId, "accountId", account.getId(),
                "revokedAccountId", owner == null || owner.getUserId().equals(appellantId) ? 0L : owner.getId());
    }

    @Transactional
    public void reject(Long adminId, Long appealId, String note) {
        Map<String, Object> appeal = lockPending(appealId);
        jdbc.update("""
                update ownership_appeal
                   set status = 'REJECTED', reviewed_by = ?, reviewed_at = now(), review_note = ?
                 where id = ?
                """, adminId, text(note), appealId);
        authService.audit(((Number) appeal.get("appellant_id")).longValue(), null, "OWNERSHIP_APPEAL_REJECTED",
                null, null, Platform.parse((String) appeal.get("platform")).getDisplayName() + " "
                        + appeal.get("handle") + "（申诉 #" + appealId + "）");
    }

    private Map<String, Object> lockPending(Long appealId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from ownership_appeal where id = ? for update", appealId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("申诉不存在");
        }
        if (!"PENDING".equals(rows.getFirst().get("status"))) {
            throw ApiException.conflict("该申诉已处理，不能重复审核");
        }
        return rows.getFirst();
    }

    private static String text(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw ApiException.validation("说明不能超过 " + MAX_TEXT + " 个字符");
        }
        return trimmed;
    }
}
