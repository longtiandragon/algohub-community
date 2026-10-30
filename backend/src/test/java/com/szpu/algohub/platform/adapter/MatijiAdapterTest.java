package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 码蹄集适配器：按官网前端接口 /exam-back/pc/queryUserDetailById.do 的真实响应结构验证。
 */
class MatijiAdapterTest {

    /** 取自真实接口的响应结构（匿名请求 userId=1 时的返回）。 */
    private static final String USER_JSON = """
            {"data":{"finishNum":7,"headimgurl":"/img/sys/hp.jpg","id":200000,"isVip":0,
            "nickname":"小码_200000","passNum":4,"passPercent":0.57,"rankOrder":12345,"school":"深圳职业技术大学",
            "userMatchRankEntity":{"isVip":0,"school":"深圳职业技术大学"}},
            "error_no":"0","queryInfo":{"recordList":[{"medalType":1}]}}
            """;

    private PlatformHttpClient httpReturning(String body) {
        PlatformHttpClient http = mock(PlatformHttpClient.class);
        when(http.postForm(eq(Platform.MATIJI), anyString(), any(), any(), any())).thenReturn(body);
        return http;
    }

    @Test
    void resolvesIdentityByNumericIdAndKeepsNicknameSeparate() {
        MatijiAdapter adapter = new MatijiAdapter(httpReturning(USER_JSON));
        var identity = adapter.resolveIdentity("200000", null);

        // 绑定以数字 ID 为准，昵称只作展示
        assertThat(identity.platformUid()).isEqualTo("200000");
        assertThat(identity.handle()).isEqualTo("小码_200000");
        assertThat(identity.extra()).containsEntry("school", "深圳职业技术大学");
    }

    @Test
    void acceptsHomepageUrlAndSendsNumericUserId() {
        PlatformHttpClient http = httpReturning(USER_JSON);
        MatijiAdapter adapter = new MatijiAdapter(http);
        adapter.resolveIdentity("https://www.matiji.net/exam/other-homepage/200000", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(http).postForm(eq(Platform.MATIJI), anyString(), form.capture(), any(), any());
        assertThat(form.getValue()).containsEntry("userId", "200000");
    }

    @Test
    void nicknameOnlyInputIsRejected() {
        MatijiAdapter adapter = new MatijiAdapter(httpReturning(USER_JSON));
        assertThatThrownBy(() -> adapter.resolveIdentity("龙天", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请先按昵称查找");
    }

    @Test
    void totalsUseOnlyPassNumAndKeepOtherFieldsAsRawExtra() {
        MatijiAdapter adapter = new MatijiAdapter(httpReturning(USER_JSON));
        var snapshot = adapter.fetchTotals(new SyncContext(1L, "小码_200000", "200000", null, null, null));

        assertThat(snapshot.solvedTotal()).isEqualTo(4);
        // finishNum 没有公开文案，不当作提交次数
        assertThat(snapshot.submissionTotal()).isNull();
        assertThat(snapshot.rawExtra()).contains("finishNum=7").contains("rankOrder=12345");
        assertThat(snapshot.difficultyBreakdown()).isEmpty();
    }

    @Test
    void capabilityDeclaresNoSubmissionHistory() {
        MatijiAdapter adapter = new MatijiAdapter(httpReturning(USER_JSON));
        var capability = adapter.capability();
        assertThat(capability.submissionHistory()).isFalse();
        assertThat(capability.totalsSnapshot()).isTrue();
        assertThat(capability.requiresCredential()).isFalse();
        assertThat(capability.difficultySource()).isEqualTo("NONE");
    }

    @Test void rankPageKeepsEveryRowAndUsesActualPageSize() {
        var adapter = new MatijiAdapter(httpReturning("""
            {"error_no":"0","data":{"total":3,"datas":[
            {"nickname":"龙天","userId":1,"school":"A","passNum":5,"rankOrder":1},
            {"nickname":"龙天","userId":2,"school":"B","passNum":4,"rankOrder":2}]}}
            """));
        var page = adapter.rankPage(0);
        assertThat(page.rows()).extracting(MatijiAdapter.Candidate::userId).containsExactly("1", "2");
        assertThat(page.nextStart()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThatThrownBy(() -> adapter.resolveIdentity("龙天123",null)).isInstanceOf(ApiException.class);
    }

    @Test void truncatedRankPageIsRejectedInsteadOfLookingLikeTheEnd() {
        assertThatThrownBy(() -> MatijiAdapter.parseRankPage("""
            {"error_no":"0","data":{"total":120,"datas":[]}}
            """, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MatijiAdapter.parseRankPage("""
            {"error_no":"0","data":{"total":3,"datas":[{"nickname":"x","userId":"abc","passNum":1}]}}
            """, 0)).isInstanceOf(ApiException.class);
    }

    @Test
    void missingUserIsReportedAsNotFound() {
        MatijiAdapter adapter = new MatijiAdapter(
                httpReturning("{\"data\":\"参数有误或者缺少参数\",\"error_no\":\"1\"}"));
        assertThatThrownBy(() -> adapter.fetchTotals(new SyncContext(1L, "1", "1", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getKind())
                .isEqualTo(ErrorKind.NOT_FOUND);
    }

    @Test
    void loginRequiredResponseIsClassifiedAsCredentialInvalid() {
        MatijiAdapter adapter = new MatijiAdapter(
                httpReturning("{\"data\":\"你还未登陆，请先登陆！\",\"error_no\":\"2\"}"));
        assertThatThrownBy(() -> adapter.fetchTotals(new SyncContext(1L, "1", "1", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getKind())
                .isEqualTo(ErrorKind.CREDENTIAL_INVALID);
    }

    @Test
    void htmlResponseIsParseErrorNotMissingUser() {
        MatijiAdapter adapter = new MatijiAdapter(httpReturning("<!doctype html><html></html>"));
        assertThatThrownBy(() -> adapter.fetchTotals(new SyncContext(1L, "1", "1", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getKind())
                .isEqualTo(ErrorKind.PARSE_ERROR);
    }
}
