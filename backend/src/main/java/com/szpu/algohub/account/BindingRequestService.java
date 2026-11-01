package com.szpu.algohub.account;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.sync.SyncService;

/**
 * 绑定申请：先证明归属，再正式绑定。
 *
 * 流程：输入平台账号 → 查询并确认身份 → 创建验证申请 → 本人把验证串写进公开资料 → 服务端核验 → 正式绑定并开始同步。
 *
 * 约束：
 * - 待验证的申请不占用平台账号：同一个 UID 可以同时有多份申请，只有核验成功的那一份受唯一约束；
 * - 验证串属于"本站用户 + 平台 UID + 这份申请"，核验时只读该 UID 的公开资料，别人的申请用不了；
 * - 重复获取不重置有效期与失败次数，过期或用过之后才签发新验证串；
 * - 外站查询不在数据库事务里：先用短事务预占一次核验机会，事务外读公开资料，
 *   再用短事务确认申请仍然有效，一次性完成验证串消费与正式绑定；
 * - 账号已被他人验证时不自动转移：证明成功后生成申诉，交给管理员审核。
 */
@Service
public class BindingRequestService {

    static final int MAX_ATTEMPTS = 10;
    /** 一次核验最长占用时间：外站查询超过它，这次核验作废，结果不能再提交。 */
    static final int ATTEMPT_SECONDS = 90;

    /** 核验结果：BOUND 已正式绑定；APPEALED 归属已被他人验证，证明成功后转为申诉。 */
    public record VerifyResult(String outcome, Long accountId, Long appealId) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformRegistry registry;
    private final PlatformAccountMapper accounts;
    private final OwnershipAppealService appeals;
    private final IdentityLookupGuard lookupGuard;
    private final AccountQuota quota;
    private final SyncService syncService;
    private final AvatarService avatars;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public BindingRequestService(JdbcTemplate jdbc, PlatformRegistry registry, PlatformAccountMapper accounts,
                                 OwnershipAppealService appeals, IdentityLookupGuard lookupGuard,
                                 SyncService syncService, AvatarService avatars,
                                 PlatformTransactionManager transactionManager, AccountQuota quota) {
        this.quota = quota;
        this.jdbc = jdbc;
        this.registry = registry;
        this.accounts = accounts;
        this.appeals = appeals;
        this.lookupGuard = lookupGuard;
        this.syncService = syncService;
        this.avatars = avatars;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 查询并确认身份：只读平台公开资料，不创建任何记录。 */
    public Map<String, Object> lookup(Long userId, String platformName, String input) {
        Platform platform = Platform.parse(platformName);
        String handle = normalize(input);
        PlatformAdapter adapter = registry.require(platform);
        // 看的是「有没有公开的账号查询」：蓝桥云课同步要登录态，但公开资料能查、能验证归属
        if (!adapter.capability().publicIdentity()) {
            throw ApiException.validation(platform.getDisplayName()
                    + " 没有公开的账号查询接口，请直接填写本人登录态添加个人关注");
        }
        lookupGuard.acquire(userId);
        ResolvedIdentity identity = requireUid(adapter.resolveIdentity(handle, null));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("platform", platform.name());
        view.put("platformUid", identity.platformUid());
        view.put("handle", identity.handle());
        view.put("displayName", identity.displayName());
        view.put("proofSupported", adapter.ownershipProof() != null);
        // 申请验证时原样交回：码蹄集要用数字 ID，不能用昵称
        view.put("lookupKey", adapter.lookupKey(identity.platformUid(), identity.handle()));
        view.putAll(relation(userId, platform.name(), identity.platformUid()));
        return view;
    }

    /**
     * 创建验证申请并签发验证串。已有未过期的申请时原样返回（不重置有效期与失败次数）；
     * 过期或已使用之后才签发新的。账号已被他人验证时仍可申请，核验成功后转为申诉。
     */
    public Map<String, Object> create(Long userId, String platformName, String input) {
        Platform platform = Platform.parse(platformName);
        String handle = normalize(input);
        PlatformAdapter adapter = registry.require(platform);
        OwnershipProof proof = adapter.ownershipProof();
        if (proof == null) {
            throw ApiException.validation(platform.getDisplayName() + " 暂不支持公开资料验证，只能添加为个人关注");
        }
        lookupGuard.acquire(userId);
        // 外站查询在事务之外，慢站点不会占着数据库连接
        ResolvedIdentity identity = requireUid(adapter.resolveIdentity(handle, null));
        String token = newToken(platform);

        return tx.execute(status -> {
            PlatformAccount mine = accounts.findMineByUid(userId, platform.name(), identity.platformUid());
            if (mine != null && "VERIFIED".equals(mine.getOwnershipStatus())) {
                throw ApiException.conflict("该账号已是你的正式绑定，无需重复验证");
            }
            // 已满的话，验证通过后也绑不上：申请前就说清楚
            quota.requireSlot(userId, platform.name(), identity.platformUid(), false);
            Long accountId = mine == null ? null : mine.getId();
            // 未过期的申请只刷新展示名，验证串、有效期与已用次数都不变
            int kept = jdbc.update("""
                    update binding_request set handle = ?, account_id = ?
                     where user_id = ? and platform = ? and platform_uid = ?
                       and verified_at is null and expires_at > clock_timestamp()
                    """, identity.handle(), accountId, userId, platform.name(), identity.platformUid());
            if (kept == 0) {
                // 并发的另一次获取刚签发的新申请不会被覆盖：只替换已过期或已使用的旧申请
                jdbc.update("""
                        insert into binding_request (user_id, platform, platform_uid, handle, account_id,
                                                     token, expires_at)
                        values (?, ?, ?, ?, ?, ?, clock_timestamp() + (? * interval '1 minute'))
                        on conflict (user_id, platform, platform_uid) do update
                           set handle = excluded.handle, account_id = excluded.account_id,
                               token = excluded.token, expires_at = excluded.expires_at,
                               attempts = 0, attempt_token = null, attempt_until = null,
                               verified_at = null, created_at = clock_timestamp()
                         where binding_request.verified_at is not null
                            or binding_request.expires_at <= clock_timestamp()
                        """, userId, platform.name(), identity.platformUid(), identity.handle(), accountId,
                        token, proof.validMinutes());
            }
            return view(jdbc.queryForMap(SELECT_REQUEST + " where user_id = ? and platform = ? and platform_uid = ?",
                    userId, platform.name(), identity.platformUid()));
        });
    }

    /** 为已登记的账号（例如待认领的旧记录）申请验证串，按平台的查询标识重新确认身份。 */
    public Map<String, Object> createFor(Long userId, String platformName, String platformUid, String handle) {
        PlatformAdapter adapter = registry.require(Platform.parse(platformName));
        return create(userId, platformName, adapter.lookupKey(platformUid, handle));
    }

    /** 我还没用掉的申请，含已过期的（过期的可以重新申请）。 */
    public List<Map<String, Object>> list(Long userId) {
        return jdbc.queryForList(SELECT_REQUEST + " where user_id = ? and platform <> 'LANQIAO' and verified_at is null order by id desc",
                userId).stream().map(this::view).toList();
    }

    public void cancel(Long userId, Long requestId) {
        int deleted = jdbc.update("delete from binding_request where id = ? and user_id = ? and verified_at is null",
                requestId, userId);
        if (deleted != 1) {
            throw ApiException.notFound("待验证的绑定申请不存在");
        }
    }

    /**
     * 核验公开资料。成功时在同一个短事务里消费验证串并正式绑定；账号已被他人验证时转为申诉。
     * 资料里没有验证串也计入次数，最多 MAX_ATTEMPTS 次；源站超时、限流、改版或防护页不计入。
     */
    public VerifyResult verify(Long userId, Long requestId) {
        // 先限频、查名额：不满足时不请求外站，也不占核验次数
        List<Map<String, Object>> pending = jdbc.queryForList(
                "select platform, platform_uid from binding_request where id = ? and user_id = ?", requestId, userId);
        if (!pending.isEmpty()) {
            lookupGuard.acquire(userId);
            quota.requireSlot(userId, (String) pending.getFirst().get("platform"),
                    (String) pending.getFirst().get("platform_uid"), false);
        }
        String attempt = UUID.randomUUID().toString();
        // 短事务（自动提交）预占一次核验机会：计入次数，并在 ATTEMPT_SECONDS 内独占这份申请
        List<Map<String, Object>> reserved = jdbc.queryForList("""
                update binding_request
                   set attempts = attempts + 1, attempt_token = ?,
                       attempt_until = clock_timestamp() + (? * interval '1 second')
                 where id = ? and user_id = ? and verified_at is null
                   and expires_at > clock_timestamp() and attempts < ?
                   and (attempt_until is null or attempt_until <= clock_timestamp())
                returning *
                """, attempt, ATTEMPT_SECONDS, requestId, userId, MAX_ATTEMPTS);
        if (reserved.isEmpty()) {
            throw unavailable(userId, requestId);
        }
        Map<String, Object> request = reserved.getFirst();
        Platform platform = Platform.parse((String) request.get("platform"));
        String uid = (String) request.get("platform_uid");
        String token = (String) request.get("token");
        try {
            PlatformAdapter adapter = registry.require(platform);
            OwnershipProof proof = adapter.ownershipProof();
            if (proof == null) {
                throw ApiException.validation(platform.getDisplayName() + " 暂不支持公开资料验证");
            }
            // 外站查询在事务之外
            ResolvedIdentity identity = adapter.resolveIdentity(
                    adapter.lookupKey(uid, (String) request.get("handle")), null);
            if (!Objects.equals(uid, identity.platformUid())) {
                throw ApiException.conflict("平台返回的 UID 与申请不一致（账号可能已改名或注销），请重新申请");
            }
            if (identity.extra() == null || !identity.extra().containsKey(proof.field())) {
                // 公开资料这一项本次没读到（源站防护、超时等），不是没写验证串
                String reason = identity.extra() == null ? null
                        : identity.extra().get(PlatformAdapter.OWNERSHIP_READ_ERROR);
                if (reason != null && !reason.isBlank()) {
                    throw new ApiException(ErrorKind.UPSTREAM_ERROR, reason);
                }
                throw new ApiException(ErrorKind.UPSTREAM_ERROR, "暂时读不到" + platform.getDisplayName()
                        + "公开资料里的这一项，请稍后再核验");
            }
            String text = identity.extra().get(proof.field());
            if (!(platform == Platform.QOJ ? token.equals(text == null ? null : text.trim()) : containsToken(text, token))) {
                throw ApiException.validation("公开资料中还没有完整的验证串"
                        + (proof.note() == null ? "" : "；" + proof.note()));
            }
            PlatformCapability capability = adapter.capability();
            VerifyResult result = tx.execute(status -> consume(userId, requestId, token, attempt, platform, uid,
                    identity, capability));
            if (result != null && result.accountId() != null) {
                // 绑定成功后顺手存一次头像（公开资料刚读过）；取不到只记日志，不影响绑定
                avatars.record(result.accountId(), uid, adapter, identity);
            }
            return result;
        } catch (ApiException e) {
            if (!e.getKind().isRetryable() && e.getKind() != ErrorKind.PARSE_ERROR) {
                throw e;
            }
            // 源站超时、限流、改版或防护页不是同学的错：退回这次核验机会
            jdbc.update("""
                    update binding_request set attempts = greatest(attempts - 1, 0)
                     where id = ? and attempt_token = ?
                    """, requestId, attempt);
            throw new ApiException(e.getKind(), e.getMessage() + "（这次不计入核验次数）");
        } catch (DuplicateKeyException e) {
            // 两份申请同时证明成功：唯一索引只放行一份，另一份整体回滚、验证串未消费
            throw ApiException.conflict("该账号归属刚被另一份申请确认，请再核验一次；核验通过后会转为申诉交管理员审核");
        } finally {
            jdbc.update("""
                    update binding_request set attempt_token = null, attempt_until = null
                     where id = ? and attempt_token = ?
                    """, requestId, attempt);
        }
    }

    private VerifyResult consume(Long userId, Long requestId, String token, String attempt, Platform platform,
                                 String uid, ResolvedIdentity identity, PlatformCapability capability) {
        // 再次确认：申请未被取消、未过期、本次核验没有超时，且验证串没有被换过
        int consumed = jdbc.update("""
                update binding_request
                   set verified_at = clock_timestamp(), attempt_token = null, attempt_until = null
                 where id = ? and user_id = ? and token = ? and attempt_token = ?
                   and verified_at is null and expires_at > clock_timestamp()
                   and attempt_until > clock_timestamp()
                """, requestId, userId, token, attempt);
        if (consumed != 1) {
            throw ApiException.conflict("核验期间申请已取消、过期或超时，请重新申请");
        }

        PlatformAccount owner = accounts.findByPlatformUid(platform.name(), uid);
        if (owner != null && !owner.getUserId().equals(userId)) {
            // 证明成功，但归属已被他人确认：不自动转移任何记录或凭据
            Long appealId = appeals.openFromProof(userId, platform.name(), uid, identity.handle(), owner, requestId);
            return new VerifyResult("APPEALED", null, appealId);
        }

        PlatformAccount account = accounts.findMineByUid(userId, platform.name(), uid);
        if (platform == Platform.QOJ && account != null && !"VERIFIED".equals(account.getOwnershipStatus())) {
            throw ApiException.conflict("旧版 QOJ 绑定需要先解除，再重新验证；旧插件数据不会自动升级归属");
        }
        if (account == null) {
            quota.requireSlot(userId, platform.name(), uid, true);
            PlatformAccount sameHandle = findMineByHandle(userId, platform.name(), identity.handle());
            if (sameHandle != null) {
                // 同名但 UID 不同：多半是改名前的旧记录，数据属于另一个 UID，不能直接合并
                throw ApiException.conflict("你已有一条同名但 UID 不同的" + platform.getDisplayName()
                        + "记录（可能是改名前的旧账号），请先解绑那条记录再核验");
            }
            account = new PlatformAccount();
            account.setUserId(userId);
            account.setPlatform(platform.name());
            account.setPlatformUid(uid);
            account.setHandle(identity.handle());
            account.setOwnershipStatus("VERIFIED");
            account.setAuthMode("PUBLIC");
            account.setSyncStatus("IDLE");
            account.setSupportsHistory(capability.submissionHistory());
            account.setNote(capability.notes());
            accounts.insert(account);
        } else {
            // 已有的待认领记录：保留账号 id、登录态与刷题记录，只改归属标记
            accounts.markVerified(account.getId(), userId, identity.handle());
        }
        jdbc.update("update binding_request set account_id = ? where id = ?", account.getId(), requestId);
        syncService.enqueueAccountSync(account.getId(), platform.name(), 90);
        return new VerifyResult("BOUND", account.getId(), null);
    }

    /** 核验机会没抢到时说清楚原因，而不是笼统报错。 */
    private ApiException unavailable(Long userId, Long requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select verified_at is not null as used,
                       expires_at <= clock_timestamp() as expired,
                       attempts >= ? as exhausted
                  from binding_request where id = ? and user_id = ?
                """, MAX_ATTEMPTS, requestId, userId);
        if (rows.isEmpty()) {
            return ApiException.notFound("绑定申请不存在或已取消");
        }
        Map<String, Object> row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("used"))) {
            return ApiException.conflict("该申请已使用，验证串不能重复核验");
        }
        if (Boolean.TRUE.equals(row.get("expired"))) {
            return ApiException.validation("申请已过期，请重新申请获取新的验证串");
        }
        if (Boolean.TRUE.equals(row.get("exhausted"))) {
            return ApiException.validation("核验次数已用完（最多 " + MAX_ATTEMPTS + " 次），请等申请过期后重新申请");
        }
        return ApiException.conflict("上一次核验仍在进行，请稍后再试");
    }

    /** 当前用户与这个平台账号的关系。只告诉申请人"已被他人验证"，不透露是谁。 */
    private Map<String, Object> relation(Long userId, String platform, String uid) {
        PlatformAccount mine = accounts.findMineByUid(userId, platform, uid);
        PlatformAccount owner = accounts.findByPlatformUid(platform, uid);
        List<Long> pending = jdbc.queryForList("""
                select id from binding_request
                 where user_id = ? and platform = ? and platform_uid = ?
                   and verified_at is null and expires_at > clock_timestamp()
                """, Long.class, userId, platform, uid);
        String state;
        if (mine != null && "VERIFIED".equals(mine.getOwnershipStatus())) {
            state = "MINE_VERIFIED";
        } else if (owner != null) {
            state = "OWNED_BY_OTHER";
        } else if (!pending.isEmpty()) {
            state = "MINE_PENDING";
        } else if (mine != null) {
            state = "MINE_UNVERIFIED";
        } else {
            state = "AVAILABLE";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("accountId", mine == null ? null : mine.getId());
        result.put("requestId", pending.isEmpty() ? null : pending.getFirst());
        return result;
    }

    private static final String SELECT_REQUEST = """
            select r.*, r.expires_at <= clock_timestamp() as expired from binding_request r
            """;

    private Map<String, Object> view(Map<String, Object> row) {
        String platform = (String) row.get("platform");
        String uid = (String) row.get("platform_uid");
        Long userId = ((Number) row.get("user_id")).longValue();
        OwnershipProof proof = registry.proofOf(Platform.parse(platform));
        PlatformAccount owner = accounts.findByPlatformUid(platform, uid);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id"));
        view.put("platform", platform);
        view.put("platformUid", uid);
        view.put("handle", row.get("handle"));
        view.put("lookupKey", registry.require(Platform.parse(platform)).lookupKey(uid, (String) row.get("handle")));
        view.put("token", row.get("token"));
        view.put("expiresAt", instant(row.get("expires_at")));
        view.put("expired", Boolean.TRUE.equals(row.get("expired")));
        view.put("attempts", row.get("attempts"));
        view.put("maxAttempts", MAX_ATTEMPTS);
        view.put("ownedByOther", owner != null && !owner.getUserId().equals(userId));
        view.put("location", proof == null ? null : proof.location());
        view.put("note", proof == null ? null : proof.note());
        view.put("validMinutes", proof == null ? null : proof.validMinutes());
        view.put("settingsUrl", switch (Platform.parse(platform)) {
            case LUOGU -> "https://www.luogu.com.cn/user/" + uid;
            case NOWCODER -> "https://ac.nowcoder.com/acm/contest/profile/" + uid;
            case QOJ -> "https://qoj.ac/user/modify-profile/"
                    + java.net.URLEncoder.encode((String) row.get("handle"), java.nio.charset.StandardCharsets.UTF_8)
                    + "/security";
            default -> proof == null ? null : proof.settingsUrl();
        });
        view.put("instructions", instructions(proof));
        return view;
    }

    private static String instructions(OwnershipProof proof) {
        if (proof == null) {
            return "该平台暂不支持公开资料验证。";
        }
        return ("motto".equals(proof.field()) ? "将「" + proof.location() + "」暂时完整替换为验证串并保存，然后点核验。" : "把验证串完整粘贴到「" + proof.location() + "」末尾并保存，然后点核验。")
                + (proof.note() == null ? "" : proof.note())
                + "有效期 " + proof.validMinutes() + " 分钟，最多核验 " + MAX_ATTEMPTS
                + " 次，重复获取不会延长；核验成功后可以删掉验证串。";
    }

    private PlatformAccount findMineByHandle(Long userId, String platform, String handle) {
        List<Long> ids = jdbc.queryForList("""
                select id from platform_account where user_id = ? and platform = ? and handle = ?
                """, Long.class, userId, platform, handle);
        return ids.isEmpty() ? null : accounts.findById(ids.getFirst());
    }

    /** 验证串必须完整出现，前后不能紧跟字母数字，防止拿更长的串蒙混。 */
    static boolean containsToken(String text, String token) {
        if (text == null || token == null) {
            return false;
        }
        return Pattern.compile("(?<![A-Za-z0-9-])" + Pattern.quote(token) + "(?![A-Za-z0-9-])")
                .matcher(text).find();
    }

    /**
     * 80 位随机数，16 个 base32 字符，整串 24 个字符：洛谷签名、码蹄集签名、AtCoder 所属这类字段都有长度限制。
     * 证明靠的是"能改这个账号的公开资料"，不是验证串保密，长度够保证不同申请不会撞上即可。
     */
    private String newToken(Platform platform) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        // 蓝桥职位字段较短；保留 80 位随机性，缩短品牌前缀后共 19 字符。
        StringBuilder token = new StringBuilder(platform == Platform.LANQIAO ? "AH-" : "AlgoHub-");
        for (int i = 0; i < 16; i++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    private static String normalize(String input) {
        if (!StringUtils.hasText(input) || input.trim().length() > 128) {
            throw ApiException.validation("账号标识不能为空，且不超过 128 个字符");
        }
        return input.trim();
    }

    private static ResolvedIdentity requireUid(ResolvedIdentity identity) {
        if (identity == null || !StringUtils.hasText(identity.platformUid())) {
            throw ApiException.validation("平台没有返回稳定的账号 UID，暂时无法绑定");
        }
        return identity;
    }

    static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) value;
    }
}
