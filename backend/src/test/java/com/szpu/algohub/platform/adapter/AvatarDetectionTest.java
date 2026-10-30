package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.platform.Avatars;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 各平台头像地址。平台默认头像也照样给（2026-09-28 定：选不选由同学自己定，不再分辨默认头像），
 * 只放行干净的 https 地址。样例是手写的最小结构，地址里的编号都是编的，不含真实用户数据。
 */
class AvatarDetectionTest {

    @Test
    void onlyCleanHttpsAddressesAreAccepted() {
        assertThat(Avatars.https("https://img.example.test/a.png")).isEqualTo("https://img.example.test/a.png");
        assertThat(Avatars.https("//img.example.test/a.png")).isEqualTo("https://img.example.test/a.png");
        assertThat(Avatars.https("  https://img.example.test/a.png  ")).isEqualTo("https://img.example.test/a.png");
        assertThat(Avatars.https("http://img.example.test/a.png")).isNull();
        assertThat(Avatars.https("javascript:alert(1)")).isNull();
        assertThat(Avatars.https("/img/a.png")).isNull();
        assertThat(Avatars.https("https://user:pw@img.example.test/a.png")).isNull();
        assertThat(Avatars.https("https://img.example.test:8443/a.png")).isNull();
        assertThat(Avatars.https("https://img.example.test/a b.png")).isNull();
        assertThat(Avatars.https("https://img.example.test/\"onerror=x")).isNull();
        assertThat(Avatars.https("https://img.example.test/" + "a".repeat(600))).isNull();
        assertThat(Avatars.https("")).isNull();
        assertThat(Avatars.https(null)).isNull();
    }

    @Test
    void codeforcesTakesTitlePhotoThenAvatarIncludingPlaceholders() throws Exception {
        var mapper = new ObjectMapper();
        var own = mapper.readTree("""
                {"handle":"sample","titlePhoto":"https://userpic.codeforces.org/1234567/title/0123456789abcdef.jpg",
                 "avatar":"https://userpic.codeforces.org/1234567/avatar/0123456789abcdef.jpg"}
                """);
        assertThat(CodeforcesAdapter.avatar(own)).isEqualTo("https://userpic.codeforces.org/1234567/title/0123456789abcdef.jpg");
        var placeholder = mapper.readTree("""
                {"handle":"sample","titlePhoto":"https://userpic.codeforces.org/no-title.jpg",
                 "avatar":"https://userpic.codeforces.org/no-avatar.jpg"}
                """);
        assertThat(CodeforcesAdapter.avatar(placeholder)).isEqualTo("https://userpic.codeforces.org/no-title.jpg");
        // 老接口返回过协议相对地址
        var relative = mapper.readTree("""
                {"handle":"sample","titlePhoto":"//userpic.codeforces.org/1234567/title/0123456789abcdef.jpg"}
                """);
        assertThat(CodeforcesAdapter.avatar(relative)).startsWith("https://userpic.codeforces.org/1234567/");
        assertThat(CodeforcesAdapter.avatar(mapper.readTree("{\"handle\":\"sample\"}"))).isNull();
    }

    @Test
    void atcoderTakesTheFirstAvatarImage() {
        assertThat(AtCoderAdapter.avatar("""
                <h3><a href="/users/sample" class="username">sample</a></h3>
                <img class='avatar' src='https://img.atcoder.jp/icons/0123456789abcdef0123456789abcdef.jpg' width='128' height='128'>
                <table class="dl-table"></table>
                """)).isEqualTo("https://img.atcoder.jp/icons/0123456789abcdef0123456789abcdef.jpg");
        assertThat(AtCoderAdapter.avatar("""
                <img class='avatar' src='//img.atcoder.jp/assets/icon/avatar.png' width='128' height='128'>
                """)).isEqualTo("https://img.atcoder.jp/assets/icon/avatar.png");
        assertThat(AtCoderAdapter.avatar("<p>no avatar</p>")).isNull();
        assertThat(AtCoderAdapter.avatar(null)).isNull();
    }

    @Test
    void leetcodeLanqiaoAndMatijiKeepWhatThePlatformGives() {
        assertThat(LeetCodeCnAdapter.avatar("https://assets.leetcode.cn/aliyun-lc-upload/default_avatar.png"))
                .isEqualTo("https://assets.leetcode.cn/aliyun-lc-upload/default_avatar.png");
        assertThat(LeetCodeCnAdapter.avatar("")).isNull();
        String lanqiao = "https://avatars.lanqiao.cn/avatar/head.png?eo-img.resize=w/200/h/200&eo-img.format=webp";
        assertThat(LanqiaoAdapter.avatar(lanqiao)).isEqualTo(lanqiao);
        // 码蹄集给的是站内路径，补成完整地址
        assertThat(MatijiAdapter.avatar("/img/sys/hp.jpg")).isEqualTo("https://www.matiji.net/img/sys/hp.jpg");
        assertThat(MatijiAdapter.avatar("")).isNull();
        assertThat(MatijiAdapter.avatar(null)).isNull();
    }

    @Test
    void nowcoderTakesTheProfileHeadPicture() {
        String page = """
                <html><head><title>样例同学的比赛主页</title></head><body>
                <div class="coder-info-wrap clearfix">
                  <a href="javascript:void(0);" class="head-pic "><img src="%s" alt=""></a>
                  <div class="coder-info-main"><div class="coder-brief">简介</div></div>
                </div></body></html>
                """;
        for (String head : new String[]{
                "https://images.nowcoder.com/images/20260101/100_1700000000000/0123456789ABCDEF0123456789ABCDEF?x-oss-process=image/resize,m_mfit,h_200,w_200",
                "https://images.nowcoder.com/head/header0001.png?x-oss-process=image/resize,m_mfit,h_100,w_100"}) {
            assertThat(NowcoderAdapter.parseIdentity("100", page.formatted(head)).extra()).containsEntry(Avatars.KEY, head);
        }
        // 页面上别处的图片（二维码之类）不算头像
        assertThat(NowcoderAdapter.avatar(Jsoup.parse("<img src=\"https://uploadfiles.nowcoder.com/app/app_download.png\">")))
                .isNull();
    }

    @Test
    void luoguUsesTheIconAddressWithoutDownloadingTheImage() {
        var http = mock(PlatformHttpClient.class);
        String url = "https://cdn.luogu.com.cn/upload/usericon/100.png";
        assertThat(new LuoguAdapter(http).avatarOf(new ResolvedIdentity("100", "sample", "sample", Map.of(Avatars.KEY, url))))
                .isEqualTo(url);
        verifyNoInteractions(http);
    }

    @Test
    void platformsWithoutAvatarsSayNo() {
        var http = mock(PlatformHttpClient.class);
        assertThat(new HduAdapter(http).avatarSupported()).isFalse();
        assertThat(new CodeforcesAdapter(http).avatarSupported()).isTrue();
        assertThat(new LuoguAdapter(http).avatarSupported()).isTrue();
    }
}
