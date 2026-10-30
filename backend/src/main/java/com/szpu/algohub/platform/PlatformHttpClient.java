package com.szpu.algohub.platform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.common.Redaction;
import com.szpu.algohub.config.AppProperties;

/**
 * 统一的出站 HTTP 客户端：共享限速、固定超时、错误分类。
 * <ul>
 *   <li>只访问各平台登记的域名（{@link OutboundPolicy}）；重定向自己跟，每一跳都核对域名，最多 5 跳；</li>
 *   <li>登录态（Cookie、附加请求头）只发给平台本身的站点，跳到第三方或静态资源域名时不带；</li>
 *   <li>响应在传输和解压两道都有字节上限，超过就停止读取；</li>
 *   <li>日志和错误信息不记录凭据内容，也不带查询参数。</li>
 * </ul>
 */
@Component
public class PlatformHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformHttpClient.class);
    private static final ScheduledThreadPoolExecutor BODY_DEADLINES = bodyDeadlines();

    private static ScheduledThreadPoolExecutor bodyDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "platform-body-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** 同一次请求最多跟随的重定向次数。 */
    static final int MAX_REDIRECTS = 5;

    /** 发请求的那一步单独抽出来：生产环境用 JDK HttpClient，测试换成替身，不连外网。 */
    interface Transport {
        Raw send(HttpRequest request) throws IOException, InterruptedException;
    }

    /** 未读的原始响应。 */
    record Raw(int status, HttpHeaders headers, InputStream body) {
    }

    /** 一次请求要发的内容；登录态单独放，按目标域名决定带不带。 */
    private record Outgoing(String method, String contentType, String body,
                            Map<String, String> headers, Map<String, String> credentialHeaders) {
    }

    private final AppProperties properties;
    private final PlatformRateLimiter rateLimiter;
    private final Transport transport;

    @Autowired
    public PlatformHttpClient(AppProperties properties, PlatformRateLimiter rateLimiter) {
        this(properties, rateLimiter, jdkTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getHttp().getConnectTimeoutSeconds()))
                // 重定向自己跟：JDK 自动跟随时会把 Cookie 原样带到新地址
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()));
    }

    PlatformHttpClient(AppProperties properties, PlatformRateLimiter rateLimiter, Transport transport) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.transport = transport;
    }

    private static Transport jdkTransport(HttpClient client) {
        return request -> {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Raw(response.statusCode(), response.headers(), response.body());
        };
    }

    public String get(Platform platform, String url) {
        return get(platform, url, Map.of(), null);
    }

    public String get(Platform platform, String url, Map<String, String> headers, String cookie) {
        return get(platform, url, headers, cookie, StandardCharsets.UTF_8);
    }

    public String get(Platform platform, String url, Map<String, String> headers, String cookie, Charset charset) {
        return execute(platform, outgoing("GET", null, null, headers, cookie), url, charset);
    }

    public String postJson(Platform platform, String url, String body, Map<String, String> headers, String cookie) {
        return execute(platform, outgoing("POST", "application/json", body, headers, cookie), url,
                StandardCharsets.UTF_8);
    }

    /** 表单 POST：部分站点的前端接口用 application/x-www-form-urlencoded。 */
    public String postForm(Platform platform, String url, Map<String, String> form,
                           Map<String, String> headers, String credential) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        return execute(platform, outgoing("POST", "application/x-www-form-urlencoded; charset=UTF-8", body,
                headers, credential), url, StandardCharsets.UTF_8);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Outgoing outgoing(String method, String contentType, String body, Map<String, String> headers,
                              String credential) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("User-Agent", properties.getHttp().getUserAgent());
        merged.put("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
        merged.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        merged.putAll(headers);
        // 凭据可能不只是 Cookie：自定义 User-Agent（Cloudflare 的放行凭据与 UA 绑定）
        // 与附加请求头（如 CSRF Token）都算登录态，只发给平台本身的站点。
        Map<String, String> secret = new LinkedHashMap<>();
        CredentialPayload payload = CredentialPayload.parse(credential);
        if (StringUtils.hasText(payload.cookie())) {
            secret.put("Cookie", payload.cookie());
        }
        if (StringUtils.hasText(payload.userAgent())) {
            secret.put("User-Agent", payload.userAgent());
        }
        if (payload.headers() != null) {
            secret.putAll(payload.headers());
        }
        return new Outgoing(method, contentType, body, Map.copyOf(merged), Map.copyOf(secret));
    }

    private HttpRequest build(Outgoing outgoing, URI uri, boolean withCredential) {
        Map<String, String> headers = new LinkedHashMap<>(outgoing.headers());
        if (withCredential) {
            headers.putAll(outgoing.credentialHeaders());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getHttp().getReadTimeoutSeconds()));
        headers.forEach(builder::header);
        if ("POST".equals(outgoing.method())) {
            builder.header("Content-Type", outgoing.contentType())
                    .POST(HttpRequest.BodyPublishers.ofString(outgoing.body(), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private String execute(Platform platform, Outgoing outgoing, String url, Charset charset) {
        if (platform == Platform.LANQIAO) {
            throw new ApiException(ErrorKind.UNSUPPORTED, "蓝桥云课同步已停用");
        }
        URI uri = OutboundPolicy.check(platform, parse(platform, url), false);
        rateLimiter.acquire(platform);
        try {
            for (int hop = 0; ; hop++) {
                Raw response = transport.send(build(outgoing, uri, OutboundPolicy.mayCarryCredential(platform, uri)));
                int status = response.status();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    close(response.body());
                    uri = redirectTarget(platform, outgoing, response, uri, hop);
                    continue;
                }
                return classify(platform, status, new String(readBody(platform, response), charset));
            }
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorKind.NETWORK, platform.getDisplayName() + " 请求超时");
        } catch (IOException e) {
            log.debug("请求 {} 失败: {}", safeUrl(uri), e.toString());
            throw new ApiException(ErrorKind.NETWORK,
                    platform.getDisplayName() + " 网络请求失败: " + Redaction.redact(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorKind.INTERNAL, "同步任务被中断");
        }
    }

    /** 只跟随 GET 的重定向；目标地址同样要在白名单里，登录态按新域名重新决定带不带。 */
    private URI redirectTarget(Platform platform, Outgoing outgoing, Raw response, URI from, int hop) {
        String name = platform.getDisplayName();
        if (!"GET".equals(outgoing.method())) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, name + " 对提交的请求返回跳转（" + response.status() + "），未跟随");
        }
        if (hop >= MAX_REDIRECTS) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, name + " 重定向次数过多，已停止");
        }
        String location = response.headers().firstValue("location")
                .orElseThrow(() -> new ApiException(ErrorKind.UPSTREAM_ERROR, name + " 返回跳转但没有目标地址"));
        URI target;
        try {
            target = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, name + " 返回的跳转地址无法解析");
        }
        URI checked = OutboundPolicy.check(platform, target, true);
        log.debug("{} 跳转到 {}", name, safeUrl(checked));
        return checked;
    }

    private static URI parse(Platform platform, String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorKind.INTERNAL, platform.getDisplayName() + " 请求地址无效");
        }
    }

    /** 读响应体：声明长度、实际传输和 gzip 解压后都不得超过上限。 */
    private byte[] readBody(Platform platform, Raw response) throws IOException {
        long max = properties.getHttp().getMaxBodyBytes();
        OptionalLong declared = response.headers().firstValueAsLong("content-length");
        if (declared.isPresent() && declared.getAsLong() > max) {
            close(response.body());
            throw tooLarge(platform, max);
        }
        byte[] bytes;
        // ofInputStream 在收到响应头后就返回，HttpRequest.timeout 不再覆盖后续流读取。
        // 到期关闭响应流以取消底层订阅，唤醒阻塞的读取，让任务进入正常退避重试。
        AtomicBoolean expired = new AtomicBoolean();
        var deadline = BODY_DEADLINES.schedule(() -> {
            expired.set(true);
            close(response.body());
        }, Math.max(1, properties.getHttp().getReadTimeoutSeconds()), TimeUnit.SECONDS);
        try (InputStream in = response.body()) {
            bytes = readUpTo(in, max, platform);
        } catch (IOException e) {
            if (expired.get()) {
                throw new HttpTimeoutException("Response body read timed out");
            }
            throw e;
        } finally {
            deadline.cancel(false);
        }
        if (expired.get()) {
            throw new HttpTimeoutException("Response body read timed out");
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            // 没要求压缩也可能收到 gzip；解压同样限量，防止几 KB 的压缩包展开成几 GB
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = readUpTo(in, max, platform);
            }
        }
        return bytes;
    }

    static byte[] readUpTo(InputStream in, long max, Platform platform) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) {
                throw tooLarge(platform, max);
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static ApiException tooLarge(Platform platform, long max) {
        return new ApiException(ErrorKind.UPSTREAM_ERROR,
                platform.getDisplayName() + " 响应超过 " + Math.max(1, max >> 20) + " MB 上限，已停止读取");
    }

    private static String classify(Platform platform, int status, String body) {
        if (status == 429) {
            throw new ApiException(ErrorKind.RATE_LIMITED, platform.getDisplayName() + " 返回限流(429)，稍后重试");
        }
        if (status == 401 || status == 403) {
            if (platform == Platform.LUOGU && status == 403) {
                throw new ApiException(ErrorKind.RATE_LIMITED, "洛谷拒绝本次请求（403），可能为防护或访问限制；保留缓存并退避重试，不自动判定 Cookie 过期");
            }
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID,
                    platform.getDisplayName() + " 返回 " + status + "，需要有效登录态或该数据不公开");
        }
        if (status >= 500) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, platform.getDisplayName() + " 源站返回 " + status);
        }
        if (status == 400) {
            // 部分平台用 400 返回业务错误（如 Codeforces 的 "handles: User not found"），
            // 交给适配器解析响应体再分类，不一律当作源站故障。
            return body;
        }
        if (status >= 300) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, platform.getDisplayName() + " 返回状态 " + status);
        }
        return body;
    }

    private static void close(InputStream body) {
        try {
            if (body != null) {
                body.close();
            }
        } catch (IOException ignored) {
            // 丢弃的响应体，关不上也不影响结果
        }
    }

    private static String safeUrl(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }
}
