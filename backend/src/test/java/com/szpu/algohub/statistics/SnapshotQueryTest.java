package com.szpu.algohub.statistics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.LoginUser;
import com.szpu.algohub.common.ApiException;

class SnapshotQueryTest {
    private final StatisticsMapper mapper = mock(StatisticsMapper.class);
    private final DailyStatMapper daily = mock(DailyStatMapper.class);
    private final SubmissionMapper submissions = mock(SubmissionMapper.class);
    private final StatisticsService service = new StatisticsService(mapper, daily, submissions);
    private final StatisticsController controller = new StatisticsController(service,
            mock(AuthService.class), mock(CampusRankingService.class));

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void authenticatedSnapshotOnlyQueriesCurrentUsersTotals() {
        var user = new AppUser();
        user.setId(23L); user.setRole("STUDENT"); user.setStatus("ACTIVE");
        var principal = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        List<Map<String, Object>> totals = List.of(Map.of("platform", "LEETCODE_CN", "solvedTotal", 42));
        when(mapper.snapshotOverview(23L)).thenReturn(totals);

        assertThat(controller.mySnapshots().data()).isEqualTo(totals);
        verify(mapper).snapshotOverview(23L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(daily, submissions);
    }

    @Test
    void anonymousSnapshotDoesNotReadStatistics() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(controller::mySnapshots).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper, daily, submissions);
    }
}
