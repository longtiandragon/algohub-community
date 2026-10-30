package com.szpu.algohub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.PlatformHttpClient.Raw;

/**
 * 出站请求安全：只访问登记的域名、重定向逐跳核对、登录态不跟到别的站点、响应与解压都限量。
 * 用替身代替网络，断言每一跳实际发出的请求。
 */
class PlatformHttpClientTest {

    private static final String COOKIE = "LEETCODE_SESSION=secret-session; csrftoken=secret-csrf";

    private final AppProperties properties = new AppProperties();
    private final List<HttpRequest> sent = new ArrayList<>();
    private final Deque<Raw> replies = new ArrayDeque<>();
    private PlatformHttpClient http;

    @BeforeEach
    void setUp() {
        properties.getHttp().setMaxBodyBytes(1024 * 1024);
        http = new PlatformHttpClient(properties, mock(PlatformRateLimiter.class), request -> {
            sent.add(request);
            Raw reply = replies.poll();
            if (reply == null) {
                throw new AssertionError("多发了一次请求：" + request.uri());
            }
            return reply;
        });
    }

    private static Raw ok(String body) {
        return new Raw(200, headers(Map.of()), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Raw ok(byte[] body, Map<String, String> headers) {
        return new Raw(200, headers(headers), new ByteArrayInputStream(body));
    }

    private static Raw redirect(int status, String location) {
        return new Raw(status, headers(Map.of("location", location)), InputStream.nullInputStream());
    }

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> map = new java.util.HashMap<>();
        values.forEach((k, v) -> map.put(k, List.of(v)));
        return HttpHeaders.of(map, (a, b) -> true);
    }

    private static String cookieOf(HttpRequest request) {
        return request.headers().firstValue("Cookie").orElse(null);
    }

    @Test
    void refusesHostsSchemesPortsAndUserInfoOutsideThePlatformList() {
        for (String url : List.of("https://evil.example/api", "http://leetcode.cn/graphql",
                "https://leetcode.cn:8443/graphql", "https://user@leetcode.cn/graphql",
                "https://leetcode.cn.evil.example/graphql", "https://codeforces.com/api/user.info")) {
            assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, url, Map.of(), COOKIE))
                    .as(url)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(ErrorKind.INTERNAL);
                        assertThat(e.getMessage()).contains("不在允许的域名内");
                    });
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void sameSiteRedirectKeepsTheCredentialAndRelativeLocationsResolve() {
        replies.add(redirect(302, "/u/someone/"));
        replies.add(ok("done"));

        assertThat(http.get(Platform.LEETCODE_CN, "https://leetcode.cn/u/Someone", Map.of(), COOKIE)).isEqualTo("done");

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).uri().toString()).isEqualTo("https://leetcode.cn/u/someone/");
        assertThat(sent).allSatisfy(request -> assertThat(cookieOf(request)).isEqualTo(COOKIE));
    }

    @Test
    void redirectToAnotherSiteIsBlockedBeforeAnythingIsSent() {
        replies.add(redirect(302, "https://collector.evil.example/steal"));

        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/graphql", Map.of(), COOKIE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ErrorKind.UPSTREAM_ERROR);
                    assertThat(e.getMessage()).contains("重定向到了站外地址").contains("collector.evil.example");
                });
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().uri().getHost()).isEqualTo("leetcode.cn");
    }

    @Test
    void downgradeToPlainHttpIsBlocked() {
        replies.add(redirect(301, "http://leetcode.cn/graphql"));
        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/graphql", Map.of(), COOKIE))
                .hasMessageContaining("站外地址");
        assertThat(sent).hasSize(1);
    }

    @Test
    void credentialIsDroppedWhenAnAllowedRedirectLeavesTheLoginSite() {
        // 牛客允许读 static.nowcoder.com 的静态题目清单，但登录态只发给 ac / www 两个站点
        String credential = "{\"cookie\":\"t=secret-t\",\"userAgent\":\"Bound-UA/1.0\",\"headers\":{\"x-csrf-token\":\"secret-x\"}}";
        replies.add(redirect(302, "https://static.nowcoder.com/book/tracker/list.json"));
        replies.add(ok("[]"));

        http.get(Platform.NOWCODER, "https://www.nowcoder.com/problem/tracker/list", Map.of(), credential);

        HttpRequest first = sent.get(0);
        HttpRequest second = sent.get(1);
        assertThat(cookieOf(first)).isEqualTo("t=secret-t");
        assertThat(first.headers().firstValue("x-csrf-token")).contains("secret-x");
        assertThat(first.headers().firstValue("User-Agent")).contains("Bound-UA/1.0");
        assertThat(cookieOf(second)).isNull();
        assertThat(second.headers().firstValue("x-csrf-token")).isEmpty();
        assertThat(second.headers().firstValue("User-Agent")).get().asString().doesNotContain("Bound-UA");
    }

    @Test
    void thirdPartyDataSourcesNeverReceiveTheCredential() {
        replies.add(ok("[]"));
        http.get(Platform.ATCODER, "https://kenkoooo.com/atcoder/atcoder-api/v3/user/submissions?user=x",
                Map.of(), "REVEL_SESSION=secret");
        assertThat(cookieOf(sent.getFirst())).isNull();
    }

    @Test
    void postRedirectsAreNotFollowed() {
        replies.add(redirect(307, "https://leetcode.cn/graphql/"));
        assertThatThrownBy(() -> http.postJson(Platform.LEETCODE_CN, "https://leetcode.cn/graphql", "{}", Map.of(), COOKIE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("未跟随"));
        assertThat(sent).hasSize(1);
    }

    @Test
    void redirectLoopsStopAfterFiveHops() {
        for (int i = 0; i <= PlatformHttpClient.MAX_REDIRECTS; i++) {
            replies.add(redirect(302, "/loop"));
        }
        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/loop"))
                .hasMessageContaining("重定向次数过多");
        assertThat(sent).hasSize(PlatformHttpClient.MAX_REDIRECTS + 1);
    }

    @Test
    void oversizedBodiesStopReadingAndDeclaredLengthIsCheckedFirst() {
        byte[] big = new byte[1024 * 1024 + 1];
        replies.add(ok(big, Map.of()));
        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/big"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("上限"));

        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("声明长度超限时不应再读响应体");
            }
        };
        replies.add(new Raw(200, headers(Map.of("content-length", "99999999")), neverRead));
        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/declared"))
                .hasMessageContaining("上限");
    }

    @Test
    void gzipIsDecompressedWithinTheLimitAndBombsAreRefused() throws Exception {
        replies.add(ok(gzip("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)), Map.of("content-encoding", "gzip")));
        assertThat(http.get(Platform.LEETCODE_CN, "https://leetcode.cn/small")).isEqualTo("{\"ok\":true}");

        // 20 MB 的零压缩后只有几十 KB：传输没超限，解压超限
        byte[] bomb = gzip(new byte[20 * 1024 * 1024]);
        assertThat(bomb.length).isLessThan(1024 * 1024);
        replies.add(ok(bomb, Map.of("content-encoding", "gzip")));
        assertThatThrownBy(() -> http.get(Platform.LEETCODE_CN, "https://leetcode.cn/bomb"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("上限"));
    }

    private static String sample(String path) throws Exception {
        try (InputStream in = PlatformHttpClientTest.class.getResourceAsStream("/samples/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void qojAnonymousRedirectToItsLoginPageEndsAsCredentialInvalid() throws Exception {
        // 实测：QOJ 对未登录请求返回 302，Location 是不带协议的 //qoj.ac/login
        replies.add(redirect(302, "//qoj.ac/login"));
        replies.add(ok(sample("qoj/login-page.html")));

        assertThatThrownBy(() -> new com.szpu.algohub.platform.adapter.QojAdapter(http).fetchTotals(
                new PlatformDtos.SyncContext(1L, "sample_user", "sample_user", null, "UOJSESSID=expired", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.CREDENTIAL_INVALID));
        assertThat(sent).extracting(r -> r.uri().toString())
                .containsExactly("https://qoj.ac/user/profile/sample_user", "https://qoj.ac/login");
        assertThat(sent).allSatisfy(r -> assertThat(cookieOf(r)).isEqualTo("UOJSESSID=expired"));
    }

    @Test
    void luoguServerSideRecordFetchIsDisabled() {
        assertThatThrownBy(() -> new com.szpu.algohub.platform.adapter.LuoguAdapter(http).fetchSubmissions(
                new PlatformDtos.SyncContext(1L, "sample_user", "100001", null, "_uid=100001; __client_id=expired", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getKind()).isEqualTo(ErrorKind.UNSUPPORTED));
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(raw);
        }
        return out.toByteArray();
    }
}
