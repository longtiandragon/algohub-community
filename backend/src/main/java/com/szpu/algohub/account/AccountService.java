package com.szpu.algohub.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.CredentialPayload;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.statistics.ContestParticipationMapper;
import com.szpu.algohub.sync.AccountDataStatusMapper;
import com.szpu.algohub.sync.SyncService;

/**
 * 平台账号绑定。绑定时用平台返回的本人 UID 校验，不只相信用户手填的账号名。
 *
 * 归属状态含义：
 * RESOLVED   平台确认该账号存在并返回了稳定 UID，但没有证明属于当前用户；
 * VERIFIED   通过独立的公开资料验证确认归属（见 BindingRequestService），同一平台 UID 至多一行；
 * MISMATCH   登录态返回的 UID 与绑定的账号不一致。
 *
 * 对同学展示的绑定类型由此推导：正式绑定（VERIFIED）、待认领（可验证但尚未验证）、
 * 个人关注（平台暂不支持验证）。
 */
@Service
public class AccountService {

    public record SyncAllItem(Long accountId, String platform, String handle, String status) {}
    public record SyncAllResult(int total, int queued, int alreadyQueued, List<SyncAllItem> accounts) {}

    private final PlatformAccountMapper accountMapper;
    private final PlatformCredentialMapper credentialMapper;
    private final CredentialCipher cipher;
    private final PlatformRegistry registry;
    private final SyncService syncService;
    private final AccountDataStatusMapper dataStatusMapper;
    private final ContestParticipationMapper contestMapper;
    private final TransactionTemplate tx;
    private final IdentityLookupGuard lookupGuard;
    private final AccountQuota quota;

    public AccountService(PlatformAccountMapper accountMapper, PlatformCredentialMapper credentialMapper,
                          CredentialCipher cipher, PlatformRegistry registry, SyncService syncService,
                          AccountDataStatusMapper dataStatusMapper, ContestParticipationMapper contestMapper,
                          PlatformTransactionManager transactionManager, IdentityLookupGuard lookupGuard,
                          AccountQuota quota) {
        this.lookupGuard = lookupGuard;
        this.quota = quota;
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
        this.registry = registry;
        this.syncService = syncService;
        this.dataStatusMapper = dataStatusMapper;
        this.contestMapper = contestMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<PlatformAccount> listMine(Long userId) {
        List<PlatformAccount> accounts = accountMapper.findByUser(userId).stream()
                .filter(account -> !"LANQIAO".equals(account.getPlatform())).toList();
        accounts.forEach(this::describeBinding);
        describeData(userId, accounts);
        return accounts;
    }

    /**
     * 数据概况：平台统计题数、已收录明细、有通过时间的题数与各数据项状态分开给出。
     * 平台统计从没取得时为 null，页面据此说"尚未取得"，而不是"0 题"。
     */
    private void describeData(Long userId, List<PlatformAccount> accounts) {
        Map<Long, Map<String, Object>> counts = new java.util.HashMap<>();
        accountMapper.solvedCounts(userId).forEach(row -> counts.put(number(row.get("accountId")), row));
        Map<Long, Map<String, Object>> totals = new java.util.HashMap<>();
        accountMapper.latestTotals(userId).forEach(row -> totals.put(number(row.get("accountId")), row));
        Map<Long, Map<String, Object>> trackers = new java.util.HashMap<>();
        accountMapper.trackerBreakdown(userId).forEach(row -> trackers.put(number(row.get("accountId")), row));
        Map<Long, Map<String, Object>> ratings = new java.util.HashMap<>();
        contestMapper.summaryByUser(userId).forEach(row -> ratings.put(number(row.get("accountId")), row));
        Map<Long, List<Map<String, Object>>> items = new java.util.HashMap<>();
        dataStatusMapper.findByUser(userId).forEach(row ->
                items.computeIfAbsent(number(row.get("accountId")), key -> new java.util.ArrayList<>()).add(row));
        for (PlatformAccount account : accounts) {
            Map<String, Object> count = counts.get(account.getId());
            account.setCollectedProblems(count == null ? 0 : (int) number(count.get("collected")));
            account.setTimedProblems(count == null ? 0 : (int) number(count.get("timed")));
            account.setTrackerProblems(count == null ? 0 : (int) number(count.get("tracker")));
            Map<String, Object> total = totals.get(account.getId());
            if (total != null) {
                account.setPlatformSolvedTotal((int) number(total.get("solvedTotal")));
                account.setPlatformSolvedAt(BindingRequestService.instant(total.get("capturedAt")));
            }
            account.setDataItems(items.getOrDefault(account.getId(), List.of()));
            Map<String, Object> tracker = trackers.get(account.getId());
            if (tracker != null) {
                account.setTrackerAccepted((int) number(tracker.get("accepted")));
                account.setTrackerMerged((int) number(tracker.get("merged")));
                account.setTrackerConflicts((int) number(tracker.get("conflicts")));
            }
            Map<String, Object> rating = ratings.get(account.getId());
            if (rating != null) {
                account.setContests((int) number(rating.get("contests")));
                account.setRatedContests((int) number(rating.get("ratedContests")));
                account.setCurrentRating(rating.get("currentRating") == null
                        ? null : (int) number(rating.get("currentRating")));
                account.setMaxRating(rating.get("maxRating") == null ? null : (int) number(rating.get("maxRating")));
            }
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /**
     * 绑定类型按归属状态与平台能力推导，不单独入库：平台以后支持了公开资料验证，
     * 原来的个人关注会自动变成"待认领"。没验证过归属的账号（RESOLVED、UNVERIFIED、MISMATCH）一律按平台能力分：
     * 能用公开资料验证的是待认领，否则是个人关注。
     */
    private void describeBinding(PlatformAccount account) {
        boolean proofSupported = registry.proofOf(Platform.parse(account.getPlatform())) != null;
        account.setProofSupported(proofSupported);
        if ("VERIFIED".equals(account.getOwnershipStatus())) {
            account.setBindingKind("VERIFIED");
        } else {
            account.setBindingKind(proofSupported ? "CLAIMABLE" : "WATCHING");
        }
    }

    public PlatformAccount requireOwned(Long accountId, Long userId, boolean admin) {
        PlatformAccount account = accountMapper.findById(accountId);
        if (account == null) {
            throw ApiException.notFound("账号不存在");
        }
        if (!admin && !account.getUserId().equals(userId)) {
            throw ApiException.forbidden("只能操作自己的绑定账号");
        }
        return account;
    }

    /**
     * 添加个人关注：只用于暂时无法证明归属的平台。账号存在即可添加，不占用归属，
     * 同一个平台账号可以被多位同学各自关注，互不影响；不参与身份认证与校内排行。
     * 能用公开资料证明归属的平台必须走绑定申请（BindingRequestService），验证后才正式绑定并同步。
     */
    public PlatformAccount bind(Long userId, String platformName, String input, String credential) {
        Platform platform = Platform.parse(platformName);
        if (platform == Platform.QOJ) return bindQojBrowser(userId, input);
        if (registry.proofOf(platform) != null) {
            throw ApiException.validation(platform.getDisplayName()
                    + " 支持公开资料验证：请先查询账号并完成归属验证，验证通过后自动绑定并同步");
        }
        PlatformAdapter adapter = registry.require(platform);
        PlatformCapability capability = adapter.capability();
        if (capability.requiresCredential() && !StringUtils.hasText(credential)) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID,
                    platform.getDisplayName() + " 需要本人登录态才能添加");
        }

        lookupGuard.acquire(userId);
        // 外站查询在事务之外
        ResolvedIdentity identity = adapter.resolveIdentity(input, credential);
        return tx.execute(status -> {
            String uid = identity.platformUid();
            if (StringUtils.hasText(uid)) {
                PlatformAccount existing = accountMapper.findMineByUid(userId, platform.name(), uid);
                if (existing != null) {
                    return refreshExisting(existing, identity, credential, capability);
                }
            }
            quota.requireSlot(userId, platform.name(), StringUtils.hasText(uid) ? uid : null, true);

            PlatformAccount account = new PlatformAccount();
            account.setUserId(userId);
            account.setPlatform(platform.name());
            account.setPlatformUid(uid);
            account.setHandle(identity.handle());
            account.setOwnershipStatus("RESOLVED");
            account.setAuthMode(StringUtils.hasText(credential) ? "COOKIE" : "PUBLIC");
            account.setSyncStatus("IDLE");
            account.setSupportsHistory(capability.submissionHistory());
            account.setNote(capability.notes());
            accountMapper.insert(account);

            if (StringUtils.hasText(credential)) {
                credentialMapper.save(account.getId(), cipher.encrypt(credential));
            }
            syncService.enqueueAccountSync(account.getId(), platform.name(), 90);
            return accountMapper.findById(account.getId());
        });
    }

    /** 浏览器关联只记录目标用户名，不把客户端提供的标识当作归属证明。 */
    private PlatformAccount bindQojBrowser(Long userId, String input) {
        String handle = input == null ? "" : input.trim();
        if (!handle.matches("[A-Za-z0-9_]{1,64}")) throw ApiException.validation("请填写 QOJ 用户名，不要填写主页链接");
        return tx.execute(status -> {
            quota.requireSlot(userId, "QOJ", handle, true);
            PlatformAccount existing = accountMapper.findMineByUid(userId, "QOJ", handle);
            if (existing != null) return existing;
            PlatformAccount account = new PlatformAccount();
            account.setUserId(userId);
            account.setPlatform("QOJ");
            account.setPlatformUid(handle);
            account.setHandle(handle);
            account.setOwnershipStatus("UNVERIFIED");
            account.setAuthMode("BROWSER");
            account.setSyncStatus("IDLE");
            account.setSupportsHistory(true);
            account.setNote("通过 QOJ 浏览器插件同步，无需 Cookie；未验证归属的记录仅用于个人统计，不参与公开排行与动态。");
            accountMapper.insert(account);
            return accountMapper.findById(account.getId());
        });
    }

    private PlatformAccount refreshExisting(PlatformAccount existing, ResolvedIdentity identity,
                                            String credential, PlatformCapability capability) {
        if (!existing.getHandle().equals(identity.handle())) {
            // 平台仅提供可变 handle 时保留标识变更记录
            accountMapper.insertIdentityHistory(existing.getId(), existing.getHandle(), identity.handle());
        }
        existing.setHandle(identity.handle());
        existing.setPlatformUid(identity.platformUid());
        if (StringUtils.hasText(credential)) {
            existing.setAuthMode("COOKIE");
            credentialMapper.save(existing.getId(), cipher.encrypt(credential));
        }
        accountMapper.updateIdentity(existing);
        syncService.enqueueAccountSync(existing.getId(), existing.getPlatform(), 90);
        return accountMapper.findById(existing.getId());
    }

    /**
     * 保存登录态。凭据可以只是 Cookie，也可以附带自定义 User-Agent 与额外请求头：
     * 有的站点（例如带 Cloudflare 挑战的页面）放行凭据与浏览器 UA 绑定，UA 不一致会重新出挑战页；
     * 有的接口还要求 Cookie 之外的 CSRF Token。
     */
    public void saveCredential(Long accountId, Long userId, boolean admin,
                               String cookie, String userAgent, Map<String, String> headers) {
        PlatformAccount account = requireOwned(accountId, userId, false);
        String credential = buildCredential(cookie, userAgent, headers);
        Platform platform = Platform.parse(account.getPlatform());
        if (platform == Platform.LUOGU || platform == Platform.QOJ) throw ApiException.validation("该平台评测记录改用浏览器同步，不保存登录态");
        PlatformAdapter adapter = registry.require(platform);
        lookupGuard.acquire(userId);
        // 外站查询在事务之外。只核对目标账号标识：公开资料只证明账号存在，不能证明凭据属于此人，
        // 所以保存登录态不会授予归属。
        ResolvedIdentity identity = adapter.resolveIdentity(
                account.getPlatformUid() != null ? account.getPlatformUid() : account.getHandle(), credential);
        boolean match = account.getPlatformUid() == null
                || account.getPlatformUid().equals(identity.platformUid());
        if (!match) {
            throw new ApiException(ErrorKind.CONFLICT, "目标账号标识发生变化，未保存凭据");
        }
        tx.executeWithoutResult(status -> {
            // 外站查询期间账号可能已被解绑或改了标识，保存前重新核对
            PlatformAccount current = requireOwned(accountId, userId, false);
            if (!java.util.Objects.equals(current.getPlatformUid(), account.getPlatformUid())) {
                throw ApiException.conflict("保存期间账号标识已改变，未保存凭据");
            }
            current.setAuthMode("COOKIE");
            current.setHandle(identity.handle());
            if (current.getPlatformUid() == null) {
                current.setPlatformUid(identity.platformUid());
            }
            accountMapper.updateIdentity(current);
            credentialMapper.save(accountId, cipher.encrypt(credential));
            syncService.enqueueAccountSync(accountId, current.getPlatform(), 80);
        });
    }

    /** 登录态附带的请求头：只允许这几个（CSRF Token 与来源类），其余一律拒绝，避免借登录态改写 Host 等请求头。 */
    static final java.util.Set<String> ALLOWED_CREDENTIAL_HEADERS =
            java.util.Set.of("x-csrf-token", "x-requested-with", "referer", "origin");
    static final int MAX_CREDENTIAL_HEADERS = 10;

    static Map<String, String> checkHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        if (headers.size() > MAX_CREDENTIAL_HEADERS) {
            throw ApiException.validation("附加请求头最多 " + MAX_CREDENTIAL_HEADERS + " 个");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        headers.forEach((rawName, rawValue) -> {
            String name = rawName == null ? "" : rawName.trim().toLowerCase(java.util.Locale.ROOT);
            if (!ALLOWED_CREDENTIAL_HEADERS.contains(name)) {
                throw ApiException.validation("不支持的附加请求头「" + (rawName == null ? "" : rawName.trim())
                        + "」，只允许 x-csrf-token、x-requested-with、referer、origin");
            }
            String value = rawValue == null ? "" : rawValue.trim();
            if (value.length() > 4096 || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw ApiException.validation("附加请求头「" + name + "」的值过长或含有换行等控制字符");
            }
            clean.put(name, value);
        });
        return clean;
    }

    private String buildCredential(String cookie, String userAgent, Map<String, String> headers) {
        CredentialPayload payload = new CredentialPayload(
                StringUtils.hasText(cookie) ? cookie.trim() : null,
                StringUtils.hasText(userAgent) ? userAgent.trim() : null,
                checkHeaders(headers));
        if (payload.isEmpty()) {
            throw ApiException.validation("登录态内容不能为空");
        }
        return payload.toStorage();
    }

    /**
     * 试用登录态但不保存：先确认这份凭据能否取到本人信息，再决定是否托管。
     * 返回内容只包含平台回传的身份信息与凭据的结构摘要，不回显凭据本身。
     */
    public Map<String, Object> testCredential(Long accountId, Long userId, boolean admin,
                                              String cookie, String userAgent, Map<String, String> headers) {
        PlatformAccount account = requireOwned(accountId, userId, false);
        String credential = buildCredential(cookie, userAgent, headers);
        Platform platform = Platform.parse(account.getPlatform());
        if (platform == Platform.LUOGU || platform == Platform.QOJ) throw ApiException.validation("该平台评测记录改用浏览器同步，不保存登录态");
        PlatformAdapter adapter = registry.require(platform);

        lookupGuard.acquire(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());
        result.put("credentialSummary", CredentialPayload.parse(credential).describe());
        ResolvedIdentity identity = adapter.resolveIdentity(
                account.getPlatformUid() != null ? account.getPlatformUid() : account.getHandle(), credential);
        result.put("resolvedUid", identity.platformUid());
        result.put("resolvedHandle", identity.handle());
        boolean match = account.getPlatformUid() == null
                || account.getPlatformUid().equals(identity.platformUid());
        result.put("uidMatchesBinding", match);
        result.put("ownershipVerified", false);
        result.put("note", match
                ? "目标账号可读取；这不证明凭据属于该账号，保存仅用于同步，归属需单独验证"
                : "返回账号标识与目标不一致，不能保存");
        return result;
    }

    /** 删除本机保存的凭据。这不代表已撤销原平台的会话，需用户在原站退出登录。 */
    @Transactional
    public void deleteCredential(Long accountId, Long userId, boolean admin) {
        requireOwned(accountId, userId, false);
        credentialMapper.delete(accountId);
    }

    @Transactional
    public void unbind(Long accountId, Long userId, boolean admin) {
        requireOwned(accountId, userId, admin);
        credentialMapper.delete(accountId);
        accountMapper.delete(accountId);
    }

    public void triggerSync(Long accountId, Long userId, boolean admin) {
        PlatformAccount account = requireOwned(accountId, userId, admin);
        if ("BROWSER".equals(account.getAuthMode())) throw ApiException.validation("请在 QOJ 浏览器插件中同步");
        registry.require(Platform.parse(account.getPlatform()));
        syncService.enqueueAccountSync(accountId, account.getPlatform(), 10);
    }

    /** 将当前用户全部绑定账号加入同步队列；已有待执行任务时保持原任务，不重复排队。 */
    public SyncAllResult triggerAllSync(Long userId) {
        List<PlatformAccount> accounts = accountMapper.findByUser(userId).stream()
                .filter(account -> !"LANQIAO".equals(account.getPlatform()) && !"BROWSER".equals(account.getAuthMode())).toList();
        List<SyncAllItem> results = new java.util.ArrayList<>(accounts.size());
        int queued = 0;
        for (PlatformAccount account : accounts) {
            boolean created = syncService.enqueueAccountSync(account.getId(), account.getPlatform(), 10);
            if (created) queued++;
            results.add(new SyncAllItem(account.getId(), account.getPlatform(), account.getHandle(),
                    created ? "QUEUED" : "ALREADY_QUEUED"));
        }
        return new SyncAllResult(accounts.size(), queued, accounts.size() - queued, results);
    }
}
