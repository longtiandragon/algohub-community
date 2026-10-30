package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

class LuoguBrowserImportRetryTest {
    private final LuoguBrowserImportService service = mock(LuoguBrowserImportService.class);
    private final LuoguBrowserImportController controller = new LuoguBrowserImportController(service);
    private final LuoguBrowserImportService.Page page = new LuoguBrowserImportService.Page(1, 0, 20, List.of());

    @Test void retriesTheSamePageAfterRolledBackLockConflict() {
        var progress = new LuoguBrowserImportService.Progress(0, 0, true, false);
        when(service.pageWithTicket("ticket", page))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(progress);
        assertThat(controller.page("ticket", page).data()).isEqualTo(progress);
        verify(service, times(2)).pageWithTicket("ticket", page);
    }

    @Test void persistentConflictHasBoundedRetriesAndAnActionableResponse() {
        when(service.pageWithTicket("ticket", page)).thenThrow(new CannotAcquireLockException("deadlock"));
        assertThatThrownBy(() -> controller.page("ticket", page))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ErrorKind.CONFLICT));
        verify(service, times(3)).pageWithTicket("ticket", page);
    }

    @Test void doesNotRetryExpiredTicketOrInvalidPage() {
        when(service.pageWithTicket("ticket", page)).thenThrow(ApiException.forbidden("expired"));
        assertThatThrownBy(() -> controller.page("ticket", page)).isInstanceOf(ApiException.class);
        verify(service).pageWithTicket("ticket", page);
    }
}
