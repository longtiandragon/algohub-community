package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 实际 SQL 回归：保留旧快照，交错归属变更，再落库凭据/公开资料更新；无需依赖线程调度。 */
@SpringBootTest
@Transactional
class AccountIdentityOwnershipRaceTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformAccountMapper accounts;

    private PlatformAccount account(String ownership) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long owner = db.queryForObject("""
                insert into app_user(username,display_name,role)
                values(?,'ownership race','STUDENT') returning id
                """, Long.class, "identity-race-" + suffix);
        long id = db.queryForObject("""
                insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode)
                values(?,'CODEFORCES',?,?,?,'PUBLIC') returning id
                """, Long.class, owner, "uid-" + suffix, "handle-" + suffix, ownership);
        return accounts.findById(id);
    }

    private void saveStaleIdentity(PlatformAccount stale) {
        stale.setHandle("updated-" + stale.getId());
        stale.setAuthMode("COOKIE");
        assertThat(accounts.updateIdentity(stale)).isEqualTo(1);
        var current = accounts.findById(stale.getId());
        assertThat(current.getHandle()).isEqualTo(stale.getHandle());
        assertThat(current.getAuthMode()).isEqualTo("COOKIE");
    }

    @Test
    void staleUnverifiedSnapshotCannotUndoCompletedOwnershipVerification() {
        var stale = account("RESOLVED");
        assertThat(accounts.markVerified(stale.getId(), stale.getUserId(), stale.getHandle())).isEqualTo(1);

        saveStaleIdentity(stale);

        assertThat(accounts.findById(stale.getId()).getOwnershipStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void staleVerifiedSnapshotCannotRestoreRevokedOwnership() {
        var stale = account("VERIFIED");
        String reason = "归属申诉已审核，当前记录转为个人关注";
        assertThat(accounts.revokeVerified(stale.getId(), reason)).isEqualTo(1);

        saveStaleIdentity(stale);

        var current = accounts.findById(stale.getId());
        assertThat(current.getOwnershipStatus()).isEqualTo("RESOLVED");
        assertThat(current.getOwnershipNote()).isEqualTo(reason);
    }

    @Test
    void staleIdentityCanStillBeSavedAfterAnotherUserReceivesOwnership() {
        var stale = account("VERIFIED");
        var recipient = account("RESOLVED");
        assertThat(accounts.revokeVerified(stale.getId(), "申诉通过")).isEqualTo(1);
        recipient.setPlatformUid(stale.getPlatformUid());
        accounts.updateIdentity(recipient);
        assertThat(accounts.markVerified(recipient.getId(), recipient.getUserId(), recipient.getHandle())).isEqualTo(1);

        // 旧实现会写回 VERIFIED，触发同一 UID 的唯一约束，整次凭据保存因此失败。
        saveStaleIdentity(stale);

        assertThat(accounts.findById(stale.getId()).getOwnershipStatus()).isEqualTo("RESOLVED");
        assertThat(accounts.findByPlatformUid("CODEFORCES", stale.getPlatformUid()).getUserId())
                .isEqualTo(recipient.getUserId());
    }
}
