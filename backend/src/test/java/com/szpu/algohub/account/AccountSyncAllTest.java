package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.sync.SyncService;

class AccountSyncAllTest {
    @Test
    void queuesOnlyCurrentUsersAccountsAndReportsExistingTasks() {
        var accounts = mock(PlatformAccountMapper.class);
        var sync = mock(SyncService.class);
        var service = new AccountService(accounts, mock(PlatformCredentialMapper.class),
                mock(CredentialCipher.class), mock(PlatformRegistry.class), sync,
                mock(com.szpu.algohub.sync.AccountDataStatusMapper.class),
                mock(com.szpu.algohub.statistics.ContestParticipationMapper.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(IdentityLookupGuard.class), mock(AccountQuota.class));
        var luogu = account(5L, 2L, "LUOGU", "sample_user_hdu");
        var qoj = account(9L, 2L, "QOJ", "sample_user");
        when(accounts.findByUser(2L)).thenReturn(List.of(luogu, qoj));
        when(sync.enqueueAccountSync(5L, "LUOGU", 10)).thenReturn(true);
        when(sync.enqueueAccountSync(9L, "QOJ", 10)).thenReturn(false);

        var result = service.triggerAllSync(2L);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.queued()).isEqualTo(1);
        assertThat(result.alreadyQueued()).isEqualTo(1);
        assertThat(result.accounts()).extracting(AccountService.SyncAllItem::status)
                .containsExactly("QUEUED", "ALREADY_QUEUED");
        verify(accounts).findByUser(2L);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void emptyBindingReturnsAnEmptyResult() {
        var accounts = mock(PlatformAccountMapper.class);
        var sync = mock(SyncService.class);
        when(accounts.findByUser(7L)).thenReturn(List.of());
        var service = new AccountService(accounts, mock(PlatformCredentialMapper.class),
                mock(CredentialCipher.class), mock(PlatformRegistry.class), sync,
                mock(com.szpu.algohub.sync.AccountDataStatusMapper.class),
                mock(com.szpu.algohub.statistics.ContestParticipationMapper.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(IdentityLookupGuard.class), mock(AccountQuota.class));

        var result = service.triggerAllSync(7L);

        assertThat(result.total()).isZero();
        assertThat(result.accounts()).isEmpty();
        verifyNoInteractions(sync);
    }

    private PlatformAccount account(Long id, Long userId, String platform, String handle) {
        var account = new PlatformAccount();
        account.setId(id); account.setUserId(userId); account.setPlatform(platform); account.setHandle(handle);
        return account;
    }
}
