package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.szpu.algohub.account.BindingRequestService.VerifyResult;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;
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
 * 先验证、后绑定。验收口径：
 * 两个用户申请同一平台账号时，未验证者不能阻塞真正所有者；并发验证最多产生一个正式归属；
 * 已有归属冲突进入申诉，审核通过只转移归属标记，不搬动任何一方的记录与登录态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountOwnershipTest {

    private static final OwnershipProof PROOF = new OwnershipProof("aboutMe", "个人简介", null, 60);

    @Autowired
    private BindingRequestService service;
    @Autowired
    private OwnershipAppealService appeals;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;
    @MockitoBean
    private PlatformRegistry registry;
    @MockitoBean
    private SyncService sync;

    private PlatformAdapter adapter;
    private PlatformAdapter watchOnly;
    private Long user;
    private Long other;
    private Long admin;

    @BeforeEach
    void setUp() {
        user = newUser("STUDENT");
        other = newUser("STUDENT");
        admin = newUser("ADMIN");
        adapter = mock(PlatformAdapter.class);
        watchOnly = mock(PlatformAdapter.class);
        when(registry.require(any())).thenReturn(adapter);
        when(registry.require(Platform.ATCODER)).thenReturn(watchOnly);
        when(registry.proofOf(any())).thenReturn(PROOF);
        when(registry.proofOf(Platform.ATCODER)).thenReturn(null);
        when(adapter.ownershipProof()).thenReturn(PROOF);
        when(adapter.lookupKey(any(), any())).thenCallRealMethod();
        when(watchOnly.lookupKey(any(), any())).thenCallRealMethod();
        when(adapter.capability()).thenReturn(new PlatformCapability(Platform.LEETCODE_CN, true, false, true,
                false, "OFFICIAL", "NONE", false, "test"));
        when(watchOnly.capability()).thenReturn(new PlatformCapability(Platform.ATCODER, true, true, true,
                false, "THIRD_PARTY", "NONE", false, "test"));
        when(watchOnly.resolveIdentity(anyString(), any()))
                .thenReturn(new ResolvedIdentity("watched-uid", "watched", "watched", Map.of()));
        profile("");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from ownership_appeal where appellant_id in (?,?,?)", user, other, admin);
        jdbc.update("delete from platform_account where user_id in (?,?,?)", user, other, admin);
        jdbc.update("delete from app_user where id in (?,?,?)", user, other, admin);
    }

    private Long newUser(String role) {
        Long id = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name, role)
                values (?, 'x', 'proof', ?) returning id
                """, Long.class, "proof-" + UUID.randomUUID(), role);
        jdbc.update("insert into verified_login_email (user_id, email) values (?, ?)", id, id + "@proof.invalid");
        return id;
    }

    /** 公开资料内容；同时断言外站查询不在数据库事务里。 */
    private void profile(String text) {
        when(adapter.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ResolvedIdentity("proof-uid", "proof-handle", "test", Map.of("aboutMe", text));
        });
    }

    private Map<String, Object> create(Long userId) {
        return service.create(userId, "LEETCODE_CN", "proof-handle");
    }

    private static Long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private String ownership(Long accountId) {
        return jdbc.queryForObject("select ownership_status from platform_account where id = ?", String.class,
                accountId);
    }

    /** 先让 other 正式拿到归属，返回其账号 id。 */
    private Long otherOwns() {
        Map<String, Object> request = create(other);
        profile((String) request.get("token"));
        return service.verify(other, id(request)).accountId();
    }

    @Test
    void verifiesOnceAndOnlyThenCreatesAnAccount() {
        Map<String, Object> request = create(user);
        assertThat(jdbc.queryForObject("select count(*) from platform_account where user_id = ?", Integer.class,
                user)).isZero();
        verifyNoInteractions(sync);

        profile((String) request.get("token"));
        VerifyResult result = service.verify(user, id(request));

        assertThat(result.outcome()).isEqualTo("BOUND");
        assertThat(ownership(result.accountId())).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("已使用");
        verify(sync).enqueueAccountSync(result.accountId(), "LEETCODE_CN", 90);
    }

    @Test
    void pendingRequestCannotReserveUidAndWrongUsersCannotVerify() {
        Map<String, Object> a = create(user);
        Map<String, Object> b = create(other);
        assertThat(a.get("token")).isNotEqualTo(b.get("token"));

        // 别人的申请不能核验
        assertThatThrownBy(() -> service.verify(other, id(a))).hasMessageContaining("申请");
        // 公开资料里只有 a 的验证串，b 的申请核验不过
        profile((String) a.get("token"));
        assertThatThrownBy(() -> service.verify(other, id(b))).hasMessageContaining("验证串");

        // 先申请的 user 没有完成验证，真正能改资料的 other 照样可以拿到归属
        profile((String) b.get("token"));
        assertThat(service.verify(other, id(b)).outcome()).isEqualTo("BOUND");

        // 之后 user 再申请，会被告知已归他人，核验成功只会转为申诉
        Map<String, Object> again = create(user);
        assertThat(again.get("ownedByOther")).isEqualTo(true);
    }

    @Test
    void requestLastsOneHourAndRepeatedRequestsDoNotResetAttemptsOrExpiry() {
        Map<String, Object> first = create(user);
        assertThatThrownBy(() -> service.verify(user, id(first))).hasMessageContaining("验证串");

        Map<String, Object> second = create(user);
        assertThat(second.get("token")).isEqualTo(first.get("token"));
        assertThat(second.get("expiresAt")).isEqualTo(first.get("expiresAt"));
        assertThat(((Number) second.get("attempts")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select extract(epoch from expires_at - clock_timestamp())::bigint from binding_request where id = ?
                """, Long.class, id(first))).isBetween(3590L, 3600L);
    }

    @Test
    void expiryDuringNetworkAndCancelCannotCommit() {
        Map<String, Object> request = create(user);
        when(adapter.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            jdbc.update("update binding_request set expires_at = clock_timestamp() where id = ?", id(request));
            return new ResolvedIdentity("proof-uid", "proof-handle", "test",
                    Map.of("aboutMe", (String) request.get("token")));
        });
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("过期");

        profile("");
        Map<String, Object> renewed = create(user);
        assertThat(renewed.get("token")).isNotEqualTo(request.get("token"));
        assertThat(((Number) renewed.get("attempts")).intValue()).isZero();
        when(adapter.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            service.cancel(user, id(renewed));
            return new ResolvedIdentity("proof-uid", "proof-handle", "test",
                    Map.of("aboutMe", (String) renewed.get("token")));
        });
        assertThatThrownBy(() -> service.verify(user, id(renewed))).hasMessageContaining("取消");
        assertThat(jdbc.queryForObject("select count(*) from platform_account where user_id = ?", Integer.class,
                user)).isZero();
    }

    @Test
    void slowUpstreamBeyondAttemptWindowCannotCommit() {
        Map<String, Object> request = create(user);
        when(adapter.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            // 模拟外站慢到超过本次核验的占用时间
            jdbc.update("update binding_request set attempt_until = clock_timestamp() where id = ?", id(request));
            return new ResolvedIdentity("proof-uid", "proof-handle", "test",
                    Map.of("aboutMe", (String) request.get("token")));
        });
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("超时");
        assertThat(jdbc.queryForObject("select verified_at is null from binding_request where id = ?",
                Boolean.class, id(request))).isTrue();
    }

    @Test
    void upstreamTroubleDoesNotConsumeAnAttempt() {
        Map<String, Object> request = create(user);
        doThrow(new ApiException(ErrorKind.NETWORK, "请求超时")).when(adapter).resolveIdentity(anyString(), any());
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("不计入");
        // 公开资料这一项没读到（例如防护页）：不是"没写验证串"
        doReturn(new ResolvedIdentity("proof-uid", "proof-handle", "test", Map.of()))
                .when(adapter).resolveIdentity(anyString(), any());
        assertThatThrownBy(() -> service.verify(user, id(request)))
                .hasMessageContaining("暂时读不到").hasMessageContaining("不计入");
        assertThat(jdbc.queryForObject("select attempts from binding_request where id = ?", Integer.class,
                id(request))).isZero();
    }

    @Test
    void numericIdPlatformsAreRequeriedByUidNotNickname() {
        // 码蹄集：昵称会重名也查不了，申请、核验与认领旧记录都按数字 ID 重新查询
        when(adapter.lookupKey(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> found = service.lookup(user, "LEETCODE_CN", "proof-handle");
        assertThat(found).containsEntry("lookupKey", "proof-uid");

        Map<String, Object> request = service.create(user, "LEETCODE_CN", (String) found.get("lookupKey"));
        assertThat(request).containsEntry("lookupKey", "proof-uid").containsEntry("handle", "proof-handle");
        profile((String) request.get("token"));
        assertThat(service.verify(user, id(request)).outcome()).isEqualTo("BOUND");
        verify(adapter, times(2)).resolveIdentity(eq("proof-uid"), any());
        verify(adapter, times(1)).resolveIdentity(eq("proof-handle"), any());
    }

    @Test
    void tokensAreShortEnoughForProfileFields() {
        String token = (String) create(user).get("token");
        assertThat(token).matches("AlgoHub-[a-z2-7]{16}");
    }

    @Test
    void lanqiaoProofFitsNineteenCharactersAndStillRequiresFullMatch() {
        Map<String, Object> request = service.create(user, "LANQIAO", "3057126");
        String token = (String) request.get("token");
        assertThat(token).matches("AH-[a-z2-7]{16}").hasSize(19);
        assertThat(BindingRequestService.containsToken(token.substring(0, 18), token)).isFalse();
        assertThat(BindingRequestService.containsToken(token, token)).isTrue();
        assertThat(service.create(user, "LANQIAO", "3057126").get("token")).isEqualTo(token);
    }

    @Test
    void profileLinksUseResolvedUid() {
        when(adapter.resolveIdentity(anyString(), any())).thenReturn(
                new ResolvedIdentity("730187", "nickname", "test", Map.of()));
        assertThat(service.create(user, "LUOGU", "nickname").get("settingsUrl"))
                .isEqualTo("https://www.luogu.com.cn/user/730187");
        when(adapter.resolveIdentity(anyString(), any())).thenReturn(
                new ResolvedIdentity("337279670", "nickname", "test", Map.of()));
        assertThat(service.create(user, "NOWCODER", "337279670").get("settingsUrl"))
                .isEqualTo("https://ac.nowcoder.com/acm/contest/profile/337279670");
    }

    @Test
    void tenFailuresRemainConsumedAndSuffixDoesNotMatch() {
        Map<String, Object> request = create(user);
        profile(request.get("token") + "extra");
        for (int i = 0; i < BindingRequestService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("验证串");
        }
        profile((String) request.get("token"));
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("次数");
    }

    @Test
    void existingPrivateAccountIsPreservedWhenAnotherUserClaimsSameUid() {
        Long old = jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status)
                values (?, 'LEETCODE_CN', 'proof-uid', 'proof-handle', 'RESOLVED') returning id
                """, Long.class, user);
        Long verified = otherOwns();
        assertThat(old).isNotEqualTo(verified);
        assertThat(ownership(old)).isEqualTo("RESOLVED");
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Map<String, Object> a = create(user);
        Map<String, Object> b = create(other);
        CountDownLatch both = new CountDownLatch(2);
        when(adapter.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            both.countDown();
            assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            return new ResolvedIdentity("proof-uid", "proof-handle", "test",
                    Map.of("aboutMe", a.get("token") + " " + b.get("token")));
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> bound(user, id(a)));
            var second = pool.submit(() -> bound(other, id(b)));
            int winners = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(winners).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from platform_account where platform_uid = 'proof-uid' and ownership_status = 'VERIFIED'
                """, Integer.class)).isEqualTo(1);
    }

    private boolean bound(Long userId, Long requestId) {
        try {
            return "BOUND".equals(service.verify(userId, requestId).outcome());
        } catch (ApiException e) {
            return false;
        }
    }

    @Test
    void lookupReportsRelationWithoutCreatingAnything() {
        assertThat(service.lookup(user, "LEETCODE_CN", "proof-handle"))
                .containsEntry("state", "AVAILABLE")
                .containsEntry("platformUid", "proof-uid")
                .containsEntry("proofSupported", true);
        assertThat(jdbc.queryForObject("select count(*) from binding_request where user_id = ?", Integer.class,
                user)).isZero();

        Map<String, Object> request = create(user);
        assertThat(service.lookup(user, "LEETCODE_CN", "proof-handle"))
                .containsEntry("state", "MINE_PENDING")
                .containsEntry("requestId", request.get("id"));

        otherOwns();
        assertThat(service.lookup(user, "LEETCODE_CN", "proof-handle")).containsEntry("state", "OWNED_BY_OTHER");
        assertThat(service.lookup(other, "LEETCODE_CN", "proof-handle")).containsEntry("state", "MINE_VERIFIED");
    }

    @Test
    void lookupIsRateLimitedPerUser() {
        for (int i = 0; i < IdentityLookupGuard.LIMIT; i++) {
            service.lookup(user, "LEETCODE_CN", "proof-handle");
        }
        assertThatThrownBy(() -> service.lookup(user, "LEETCODE_CN", "proof-handle"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED));
        // 其他同学不受影响
        assertThat(service.lookup(other, "LEETCODE_CN", "proof-handle")).containsEntry("state", "AVAILABLE");
    }

    @Test
    void provenClaimOnOwnedAccountBecomesAppealAndApprovalMovesOnlyTheFlag() {
        Long ownerAccount = otherOwns();
        // 原归属者已有登录态与刷题快照，申诉通过后都必须原样保留
        jdbc.update("insert into platform_credential (account_id, cipher_text) values (?, 'cipher')", ownerAccount);
        jdbc.update("insert into total_snapshot (account_id, solved_total) values (?, 42)", ownerAccount);

        Map<String, Object> request = create(user);
        assertThat(request.get("ownedByOther")).isEqualTo(true);
        profile((String) request.get("token"));
        VerifyResult result = service.verify(user, id(request));

        assertThat(result.outcome()).isEqualTo("APPEALED");
        assertThat(result.accountId()).isNull();
        assertThat(ownership(ownerAccount)).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> service.verify(user, id(request))).hasMessageContaining("已使用");
        assertThat(appeals.mine(user)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
        assertThat(appeals.list("PENDING")).anySatisfy(row -> assertThat(row)
                .containsEntry("id", result.appealId())
                .containsEntry("currentOwnerId", other)
                .containsEntry("appellantId", user));

        Map<String, Object> approved = appeals.approve(admin, result.appealId(), "申请人现场演示可修改简介");

        Long newAccount = ((Number) approved.get("accountId")).longValue();
        assertThat(newAccount).isNotEqualTo(ownerAccount);
        assertThat(ownership(newAccount)).isEqualTo("VERIFIED");
        assertThat(ownership(ownerAccount)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select ownership_note from platform_account where id = ?", String.class,
                ownerAccount)).contains("记录与登录态均保留");
        assertThat(jdbc.queryForObject("select count(*) from platform_credential where account_id = ?",
                Integer.class, ownerAccount)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from total_snapshot where account_id = ?",
                Integer.class, ownerAccount)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform_credential where account_id = ?",
                Integer.class, newAccount)).isZero();
        verify(sync).enqueueAccountSync(newAccount, "LEETCODE_CN", 90);
        assertThat(jdbc.queryForList("select event from auth_event where user_id = ?", String.class, other))
                .contains("OWNERSHIP_REVOKED");
        assertThat(jdbc.queryForList("select event from auth_event where user_id = ?", String.class, user))
                .contains("OWNERSHIP_APPEAL_OPENED", "OWNERSHIP_APPEAL_APPROVED");

        assertThatThrownBy(() -> appeals.approve(admin, result.appealId(), null)).hasMessageContaining("已处理");
    }

    @Test
    void rejectedAppealLeavesOwnershipUntouched() {
        Long ownerAccount = otherOwns();
        Map<String, Object> request = create(user);
        profile((String) request.get("token"));
        VerifyResult result = service.verify(user, id(request));

        appeals.reject(admin, result.appealId(), "无法确认");

        assertThat(ownership(ownerAccount)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from platform_account where user_id = ?", Integer.class,
                user)).isZero();
        assertThat(appeals.mine(user)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "REJECTED").containsEntry("reviewNote", "无法确认"));
        // 只有原归属者验证时排过一次同步，驳回不会给申请人建账号或排同步
        verify(sync, times(1)).enqueueAccountSync(any(), eq("LEETCODE_CN"), eq(90));
        verify(sync).enqueueAccountSync(ownerAccount, "LEETCODE_CN", 90);
    }

    @Test
    void appellantsExistingWatchRowIsUpgradedInPlace() {
        Long watched = jdbc.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, auth_mode)
                values (?, 'LEETCODE_CN', 'proof-uid', 'proof-handle', 'RESOLVED', 'COOKIE') returning id
                """, Long.class, user);
        jdbc.update("insert into platform_credential (account_id, cipher_text) values (?, 'mine')", watched);
        Long ownerAccount = otherOwns();

        Map<String, Object> request = create(user);
        profile((String) request.get("token"));
        Long appealId = service.verify(user, id(request)).appealId();
        Map<String, Object> approved = appeals.approve(admin, appealId, null);

        assertThat(((Number) approved.get("accountId")).longValue()).isEqualTo(watched);
        assertThat(ownership(watched)).isEqualTo("VERIFIED");
        assertThat(ownership(ownerAccount)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select cipher_text from platform_credential where account_id = ?",
                String.class, watched)).isEqualTo("mine");
    }

    @Test
    void appellantCanExplainOrWithdrawOnlyOwnPendingAppeal() {
        otherOwns();
        Map<String, Object> request = create(user);
        profile((String) request.get("token"));
        Long appealId = service.verify(user, id(request)).appealId();

        appeals.updateReason(user, appealId, "这是我高一注册的账号");
        assertThatThrownBy(() -> appeals.updateReason(other, appealId, "冒充")).hasMessageContaining("没有找到");
        assertThatThrownBy(() -> appeals.withdraw(other, appealId)).hasMessageContaining("没有找到");

        appeals.withdraw(user, appealId);
        assertThat(appeals.mine(user)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "WITHDRAWN")
                        .containsEntry("reason", "这是我高一注册的账号"));
        assertThatThrownBy(() -> appeals.approve(admin, appealId, null)).hasMessageContaining("已处理");
    }

    @Test
    void unsupportedPlatformsAreWatchOnlyAndMayBeSharedByManyUsers() {
        PlatformAccount mine = accountService.bind(user, "ATCODER", "watched", null);
        PlatformAccount theirs = accountService.bind(other, "ATCODER", "watched", null);

        assertThat(mine.getId()).isNotEqualTo(theirs.getId());
        assertThat(ownership(mine.getId())).isEqualTo("RESOLVED");
        assertThat(ownership(theirs.getId())).isEqualTo("RESOLVED");
        assertThatThrownBy(() -> service.create(user, "ATCODER", "watched")).hasMessageContaining("个人关注");
        // 支持公开资料验证的平台不能跳过验证直接添加
        assertThatThrownBy(() -> accountService.bind(user, "LEETCODE_CN", "proof-handle", null))
                .hasMessageContaining("归属验证");
    }

    @Test
    void bindingKindIsDerivedFromOwnershipAndPlatformProofSupport() {
        otherOwns();
        jdbc.update("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status)
                values (?, 'LEETCODE_CN', 'proof-uid', 'proof-handle', 'RESOLVED'),
                       (?, 'ATCODER', 'watched-uid', 'watched', 'RESOLVED'),
                       (?, 'CODEFORCES', 'unverified-cf-uid', 'unverified-cf', 'UNVERIFIED'),
                       (?, 'ATCODER', 'unverified-ac-uid', 'unverified-ac', 'UNVERIFIED')
                """, user, user, user, user);

        Map<String, String> kinds = new java.util.HashMap<>();
        accountService.listMine(user).forEach(a -> kinds.put(a.getHandle(), a.getBindingKind()));
        // 没验证过归属的账号不论是 RESOLVED 还是 UNVERIFIED，都按平台能不能验证分成待认领 / 个人关注
        assertThat(kinds).containsEntry("proof-handle", "CLAIMABLE")
                .containsEntry("watched", "WATCHING")
                .containsEntry("unverified-cf", "CLAIMABLE")
                .containsEntry("unverified-ac", "WATCHING");
        assertThat(accountService.listMine(other)).singleElement()
                .satisfies(a -> assertThat(a.getBindingKind()).isEqualTo("VERIFIED"));
    }

    @Test
    void appealReviewIsAdminOnly() throws Exception {
        String studentToken = authService.issueSession(userMapper.findById(user), "127.0.0.1", "JUnit",
                "LOGIN_SUCCESS").view().accessToken();
        String adminToken = authService.issueSession(userMapper.findById(admin), "127.0.0.1", "JUnit",
                "LOGIN_SUCCESS").view().accessToken();

        mockMvc.perform(get("/api/admin/ownership-appeals").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/ownership-appeals").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ownership-appeals").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/binding-requests/lookup").param("platform", "LEETCODE_CN")
                        .param("handle", "proof-handle"))
                .andExpect(status().isUnauthorized());
    }

    @Autowired
    private AccountQuota quota;
    @Autowired
    private com.szpu.algohub.auth.AuthRateLimit rateLimit;

    @Test
    void atMostThreeAccountsPerPlatformCountingWatchClaimableAndVerified() {
        when(watchOnly.resolveIdentity(anyString(), any())).thenAnswer(invocation -> {
            String handle = invocation.getArgument(0);
            return new ResolvedIdentity("uid-" + handle, handle, handle, Map.of());
        });
        for (int i = 1; i <= quota.maxPerPlatform(); i++) {
            accountService.bind(user, "ATCODER", "w" + i, null);
        }
        assertThatThrownBy(() -> accountService.bind(user, "ATCODER", "w9", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ErrorKind.CONFLICT);
                    assertThat(e.getMessage()).contains("最多添加 3 个账号");
                });
        // 已有的账号重新添加只是刷新，不占新名额；其他同学不受影响
        assertThat(accountService.bind(user, "ATCODER", "w1", null).getHandle()).isEqualTo("w1");
        assertThat(accountService.bind(other, "ATCODER", "w9", null).getHandle()).isEqualTo("w9");

        // 待认领的旧记录也算：满了就不能再申请验证别的账号
        for (int i = 1; i <= quota.maxPerPlatform(); i++) {
            jdbc.update("""
                    insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, auth_mode, sync_status)
                    values (?, 'LEETCODE_CN', ?, ?, 'RESOLVED', 'PUBLIC', 'IDLE')
                    """, user, "old-" + i, "old-" + i);
        }
        assertThatThrownBy(() -> create(user)).hasMessageContaining("最多添加 3 个账号");
        verify(adapter, times(1)).resolveIdentity(anyString(), any());
    }

    @Test
    void outboundGuardAlsoLimitsPerIpAcrossUsers() {
        IdentityLookupGuard guard = new IdentityLookupGuard(rateLimit, 100, 3, 10);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("198.51.100." + (int) (Math.random() * 250) + "-" + UUID.randomUUID());
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            guard.acquire(user);
            guard.acquire(other, 2);
            assertThatThrownBy(() -> guard.acquire(admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED);
                        assertThat(e.getMessage()).contains("当前网络");
                    });
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
        // 不在请求线程里（后台任务）只按用户计数
        guard.acquire(admin);
    }
}
