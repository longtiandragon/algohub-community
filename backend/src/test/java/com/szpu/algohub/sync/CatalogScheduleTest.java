package com.szpu.algohub.sync;

import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.statistics.SubmissionMapper;
import java.time.Instant;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CatalogScheduleTest {
    @Test void refreshesAllThreePlatformsWithoutDependingOnAccountActivity() {
        var service = mock(SyncService.class);
        var mapper = mock(SyncTaskMapper.class);
        var worker = new SyncWorker(service, mapper, mock(PlatformAccountMapper.class),
                mock(SubmissionMapper.class), new AppProperties());
        try {
            worker.scheduleCatalogRefresh();
            for (String p : java.util.List.of("CODEFORCES", "ATCODER", "NOWCODER")) verify(service).enqueueCatalogRefresh(p);
        } finally { worker.shutdown(); }
    }

    @Test void usesPersistentAttemptTimeAcrossRestartsAndSupportsDisabling() {
        var service = mock(SyncService.class);
        var mapper = mock(SyncTaskMapper.class);
        var properties = new AppProperties();
        when(mapper.latestCatalogAttempt("CODEFORCES")).thenReturn(Instant.now().minus(Duration.ofHours(23)));
        when(mapper.latestCatalogAttempt("ATCODER")).thenReturn(Instant.now().minus(Duration.ofHours(25)));
        when(mapper.latestCatalogAttempt("NOWCODER")).thenReturn(Instant.now());
        var worker = new SyncWorker(service, mapper, mock(PlatformAccountMapper.class),
                mock(SubmissionMapper.class), properties);
        try {
            worker.scheduleCatalogRefresh();
            verify(service).enqueueCatalogRefresh("ATCODER");
            verifyNoMoreInteractions(service);
            properties.getSync().setCatalogIntervalMinutes(0);
            worker.scheduleCatalogRefresh();
            verifyNoMoreInteractions(service);
        } finally { worker.shutdown(); }
    }
}
