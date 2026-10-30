package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 新增的公开资料证明字段。样本按 2026-09-23 实际返回整理（只保留相关结构，个人信息已替换）：
 * AtCoder 个人页资料表的 Affiliation 行；洛谷公开主页 JSON 的 data.user.slogan；码蹄集公开资料的 signature。
 */
class OwnershipProofFieldsTest {

    /** AtCoder 个人页资料表：填了 Affiliation 才有这一行。 */
    private static final String ATCODER_WITH_AFFILIATION = """
            <table class="dl-table">
                <tr><th class="no-break">Country/Region</th><td><img src="//img.atcoder.jp/assets/flag/CN.png"> China</td></tr>
                <tr><th class="no-break">Birth Year</th><td>2006</td></tr>
                <tr><th class="no-break">Affiliation</th><td class="break-all">SZPU AlgoHub-abcdefghijklmnop</td></tr>
            </table>
            <table class="dl-table mt-2">
                <tr><th class="no-break">Rank</th><td>34577th</td></tr>
            </table>
            """;
    private static final String ATCODER_WITHOUT_AFFILIATION = """
            <table class="dl-table">
                <tr><th class="no-break">Country/Region</th><td>China</td></tr>
                <tr><th class="no-break">Birth Year</th><td>2006</td></tr>
            </table>
            """;

    /** Codeforces user.info：按 2026-09-24 实际返回整理，handle 与头像编号已替换。 */
    private static final String CODEFORCES_USER_INFO = """
            {"status":"OK","result":[{"lastName":"AlgoHub-abcdefghijklmnop","lastOnlineTimeSeconds":1790233529,
             "rating":1380,"friendOfCount":1,"titlePhoto":"https://userpic.codeforces.org/1234567/title/0123456789abcdef.jpg",
             "handle":"sample_handle","avatar":"https://userpic.codeforces.org/1234567/avatar/0123456789abcdef.jpg",
             "contribution":0,"organization":"SZPU","rank":"pupil","maxRating":1424,"registrationTimeSeconds":1700000000,
             "maxRank":"specialist"}]}
            """;

    @Test
    void codeforcesProofReadsTheEnglishLastNameAndKeepsTheHandleAsDisplayName() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(CODEFORCES_USER_INFO);
        var identity = CodeforcesAdapter.parseIdentity(root, "sample_handle");
        assertThat(identity.platformUid()).isEqualTo("1234567");
        assertThat(identity.displayName()).isEqualTo("sample_handle");
        assertThat(identity.extra()).containsEntry("lastName", "AlgoHub-abcdefghijklmnop")
                .containsEntry("organization", "SZPU");
        // 没填姓氏：读到了、是空的——核验时提示还没有验证串
        var noName = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(CODEFORCES_USER_INFO.replace("\"lastName\":\"AlgoHub-abcdefghijklmnop\",", ""));
        assertThat(CodeforcesAdapter.parseIdentity(noName, "sample_handle").extra()).containsEntry("lastName", "");
        var proof = new CodeforcesAdapter(mock(PlatformHttpClient.class)).ownershipProof();
        assertThat(proof.field()).isEqualTo("lastName");
        assertThat(proof.location()).contains("姓氏");
    }

    @Test
    void atcoderAffiliationIsReadFromTheProfileTable() {
        assertThat(AtCoderAdapter.affiliation(ATCODER_WITH_AFFILIATION)).isEqualTo("SZPU AlgoHub-abcdefghijklmnop");
        assertThat(AtCoderAdapter.affiliation(ATCODER_WITHOUT_AFFILIATION)).isEmpty();
        assertThat(AtCoderAdapter.affiliation(null)).isEmpty();
        // 向导里「打开 AtCoder 设置页」直接到能改 Affiliation 的页面
        var proof = new AtCoderAdapter(mock(PlatformHttpClient.class)).ownershipProof();
        assertThat(proof.location()).contains("Affiliation（机构）");
        assertThat(proof.settingsUrl()).isEqualTo("https://atcoder.jp/settings");
        assertThat(new LeetCodeCnAdapter(mock(PlatformHttpClient.class)).ownershipProof().settingsUrl()).isEqualTo("https://leetcode.cn/settings/profile/");
        assertThat(new MatijiAdapter(mock(PlatformHttpClient.class)).ownershipProof().settingsUrl()).isEqualTo("https://www.matiji.net/exam/personalcenter/homepage");
        assertThat(new CodeforcesAdapter(mock(PlatformHttpClient.class)).ownershipProof().settingsUrl())
                .isEqualTo("https://codeforces.com/settings/social");
    }

    @Test
    void luoguSloganMustBelongToTheSameUidAndChallengePagesAreNotEmptySlogans() {
        String json = """
                {"instance":"main","template":"user.show","status":200,"data":{"prizes":[],"elo":[],
                 "dailyCounts":{},"user":{"uid":100001,"name":"sample","slogan":"练习中 AlgoHub-abcdefghijklmnop",
                 "introduction":"","color":"Gray","ccfLevel":0}}}
                """;
        assertThat(LuoguAdapter.slogan(json, "100001")).isEqualTo("练习中 AlgoHub-abcdefghijklmnop");
        assertThatThrownBy(() -> LuoguAdapter.slogan(json, "999"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.PARSE_ERROR));
        assertThatThrownBy(() -> LuoguAdapter.slogan("<html>Welcome - Luogu Spilopelia</html>", "100001"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.RATE_LIMITED));
        String noSlogan = json.replace("\"slogan\":\"练习中 AlgoHub-abcdefghijklmnop\",", "");
        assertThat(LuoguAdapter.slogan(noSlogan, "100001")).isEmpty();
    }

    @Test
    void luoguIdentityStillResolvesWhenTheProfilePageIsBlocked() {
        PlatformHttpClient http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.LUOGU), anyString(), anyMap(), isNull())).thenAnswer(invocation -> {
            String url = invocation.getArgument(1);
            if (url.contains("/api/user/search")) {
                return "{\"users\":[{\"uid\":100001,\"name\":\"sample\",\"color\":\"Gray\",\"ccfLevel\":0}]}";
            }
            return "<html>Welcome - Luogu Spilopelia</html>";
        });
        var identity = new LuoguAdapter(http).resolveIdentity("sample", null);
        assertThat(identity.platformUid()).isEqualTo("100001");
        // 读不到主页时不放空签名进去：核验会提示"暂时读不到"，而不是"没写验证串"
        assertThat(identity.extra()).doesNotContainKey("slogan");
    }

    @Test
    void matijiSignatureComesWithThePublicProfile() {
        PlatformHttpClient http = mock(PlatformHttpClient.class);
        when(http.postForm(eq(Platform.MATIJI), anyString(), anyMap(), anyMap(), isNull())).thenReturn("""
                {"error_no":"0","data":{"id":123456,"nickname":"龙天","school":"深职大","passNum":88,
                 "passPercent":"50%","signature":"AlgoHub-abcdefghijklmnop","finishNum":90}}
                """);
        var identity = new MatijiAdapter(http).resolveIdentity("123456", null);
        assertThat(identity.extra()).containsEntry("signature", "AlgoHub-abcdefghijklmnop");
        assertThat(new MatijiAdapter(http).ownershipProof().field()).isEqualTo("signature");
    }
}
