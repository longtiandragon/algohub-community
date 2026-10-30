package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.adapter.MatijiAdapter;
import com.szpu.algohub.platform.adapter.MatijiAdapter.Candidate;
import com.szpu.algohub.platform.adapter.MatijiAdapter.RankPage;

/** 码蹄集昵称索引：翻到的整页都记下来，同名直接给候选，后台逐页补全、翻完一轮从头再来。 */
@SpringBootTest
class MatijiIndexTest {

    @Autowired
    private MatijiIndexMapper mapper;
    @Autowired
    private IdentityLookupGuard lookupGuard;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final MatijiAdapter adapter = mock(MatijiAdapter.class);
    private MatijiIndexService service;

    private Long userId;

    @BeforeEach
    void setUp() {
        // 只替换外站请求：索引表、限频与事务都是真的
        service = new MatijiIndexService(adapter, mapper, lookupGuard, transactionManager);
        jdbc.execute("delete from matiji_user_index where uid like '9900%'");
        jdbc.execute("update matiji_index_state set next_start = 0, total = null, round_started = null, last_error = null");
        userId = jdbc.queryForObject("insert into app_user (username, password_hash, display_name) values (?, 'x', 'mtj') returning id",
                Long.class, "matiji-index-" + UUID.randomUUID());
        when(adapter.rankPage(0)).thenReturn(new RankPage(List.of(
                new Candidate("99001", "龙天", "深职大", 120, 1),
                new Candidate("99002", "张三", "某学院", 90, 2),
                new Candidate("99003", "龙天", "另一所学校", 30, 3)), 3, 5));
        when(adapter.rankPage(3)).thenReturn(new RankPage(List.of(
                new Candidate("99004", "李四", "", 10, 4),
                new Candidate("99005", "王五", "", 5, 5)), 5, 5));
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("delete from matiji_user_index where uid like '9900%'");
        jdbc.execute("update matiji_index_state set next_start = 0, total = null, round_started = null, last_error = null");
        jdbc.update("delete from app_user where id = ?", userId);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> uids(Map<String, Object> result) {
        return ((List<?>) result.get("candidates")).stream()
                .map(row -> row instanceof Candidate c ? (Object) c.userId() : ((Map<String, Object>) row).get("userId"))
                .toList();
    }

    @Test
    void liveScanIndexesTheWholePageAndLookupNeedsNoUpstream() {
        Map<String, Object> scanned = service.scan(userId, "龙天", 0);
        assertThat(uids(scanned)).containsExactly("99001", "99003");
        assertThat(scanned).containsEntry("nextStart", 3).containsEntry("hasMore", true);
        // 不同名的账号也记进了索引
        assertThat(jdbc.queryForObject("select count(*) from matiji_user_index where uid like '9900%'", Integer.class))
                .isEqualTo(3);

        Map<String, Object> indexed = service.lookup("龙天");
        assertThat(uids(indexed)).containsExactly("99001", "99003");
        assertThat(indexed).containsEntry("fromIndex", true);
        verify(adapter).rankPage(0);
        assertThat(uids(service.lookup("没这个人"))).isEmpty();
    }

    @Test
    void backgroundCrawlWalksPagesAndStartsANewRoundAtTheEnd() {
        service.crawl(1);
        assertThat(jdbc.queryForMap("select next_start, total from matiji_index_state"))
                .containsEntry("next_start", 3).containsEntry("total", 5);

        service.crawl(5);
        Map<String, Object> state = jdbc.queryForMap("select next_start, round_started from matiji_index_state");
        assertThat(state).containsEntry("next_start", 0);
        assertThat(state.get("round_started")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from matiji_user_index where uid like '9900%'", Integer.class))
                .isEqualTo(5);
        assertThat(uids(service.lookup("王五"))).containsExactly("99005");
    }

    @Test
    void upstreamFailureKeepsThePositionAndRecordsTheReason() {
        jdbc.execute("update matiji_index_state set next_start = 3");
        when(adapter.rankPage(3)).thenThrow(new ApiException(ErrorKind.RATE_LIMITED, "码蹄集限流"));

        service.crawl(3);

        assertThat(jdbc.queryForMap("select next_start, last_error from matiji_index_state"))
                .containsEntry("next_start", 3).containsEntry("last_error", "码蹄集限流");
        verify(adapter, never()).rankPage(0);
    }

    @Test
    void liveScanIsRateLimitedPerUser() {
        for (int i = 0; i < IdentityLookupGuard.LIMIT; i++) {
            service.scan(userId, "龙天", 0);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.scan(userId, "龙天", 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED));
        verify(adapter, org.mockito.Mockito.times(IdentityLookupGuard.LIMIT)).rankPage(anyInt());
    }
}
