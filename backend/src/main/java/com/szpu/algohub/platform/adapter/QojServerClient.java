package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Only the local collector receives this service token; QOJ cookies never enter AlgoHub. */
@Component
public class QojServerClient {
    private final boolean enabled;
    private final String tokenFile;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public QojServerClient(@Value("${algohub.qoj.server-enabled:false}") boolean enabled,
                           @Value("${algohub.qoj.collector-token-file:}") String tokenFile,
                           ObjectMapper json) {
        this.enabled = enabled;
        this.tokenFile = tokenFile;
        this.json = json;
    }

    public boolean enabled() { return enabled; }

    static String handle(String input) {
        String value = input == null ? "" : input.trim();
        if (!value.matches("[A-Za-z0-9_]{1,64}")) throw ApiException.validation("请填写 QOJ 用户名，不要填写主页链接");
        return value;
    }

    public JsonNode profile(String handle) { return fetch("profile", handle, 1); }
    JsonNode submissions(String handle, int page) { return fetch("submissions", handle, page); }

    private JsonNode fetch(String kind, String input, int page) {
        if (!enabled) throw new ApiException(ErrorKind.UNSUPPORTED, "QOJ 服务器采集尚未启用");
        String handle = handle(input);
        if (page < 1 || page > 5000) throw ApiException.validation("QOJ 分页超出范围");
        try {
            String token = Files.readString(Path.of(tokenFile)).trim();
            if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+"))
                throw new ApiException(ErrorKind.UPSTREAM_ERROR, "QOJ 平台采集配置异常，请联系平台管理员");
            String route = "/" + kind + "?handle=" + handle + (kind.equals("submissions") ? "&page=" + page : "");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9224" + route))
                    .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            JsonNode root;
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw invalid();
                root = json.readTree(bytes);
            }
            if (root == null) throw invalid();
            if (root.hasNonNull("error")) throw failure(root.path("error").asText());
            if (response.statusCode() != 200 || !kind.equals(root.path("kind").asText())
                    || !handle.equals(root.path("handle").asText()) || root.path("page").asInt() != page
                    || !root.path("data").isObject()) throw invalid();
            return root.get("data");
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorKind.NETWORK, "QOJ 采集请求已中断，已有统计保留");
        } catch (Exception e) {
            // Do not attach source exceptions: HTTP/config exceptions may contain credentials.
            throw new ApiException(ErrorKind.NETWORK, "QOJ 平台采集服务暂不可用，请联系平台管理员；已有统计保留");
        }
    }

    static ApiException failure(String code) {
        return switch (code) {
            case "SESSION_EXPIRED" -> new ApiException(ErrorKind.UPSTREAM_ERROR,
                    "QOJ 平台会话已过期，等待平台管理员恢复；无需提交个人 Cookie，已有统计保留");
            case "ACCESS_RESTRICTED" -> new ApiException(ErrorKind.FORBIDDEN,
                    "QOJ 限制平台账号访问，等待平台管理员处理；已有统计保留");
            case "RATE_LIMITED" -> new ApiException(ErrorKind.RATE_LIMITED, "QOJ 限流，平台采集已暂停重试");
            case "NETWORK" -> new ApiException(ErrorKind.NETWORK, "QOJ 平台采集网络异常，已有统计保留");
            case "UPSTREAM_ERROR" -> new ApiException(ErrorKind.UPSTREAM_ERROR, "QOJ 暂时不可用，已有统计保留");
            case "NOT_FOUND" -> ApiException.notFound("QOJ 用户不存在或当前不可见");
            default -> invalid();
        };
    }

    static ApiException invalid() {
        return new ApiException(ErrorKind.PARSE_ERROR, "QOJ 页面或分页校验失败，已有统计保留");
    }
}
