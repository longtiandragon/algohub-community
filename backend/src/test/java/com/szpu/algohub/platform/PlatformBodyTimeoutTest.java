package com.szpu.algohub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

class PlatformBodyTimeoutTest {
    @Test
    @Timeout(15)
    void headersWithoutBodyCannotHoldWorkerAndNextRequestStillSucceeds() throws Exception {
        verifyStalledBody(false);
    }

    @Test
    @Timeout(15)
    void partialBodyCannotHoldWorkerAndNextRequestStillSucceeds() throws Exception {
        verifyStalledBody(true);
    }

    private void verifyStalledBody(boolean partial) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stall", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                if (partial) {
                    exchange.getResponseBody().write('a');
                    exchange.getResponseBody().flush();
                }
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(new byte[] {'o', 'k'});
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            AppProperties properties = new AppProperties();
            properties.getHttp().setReadTimeoutSeconds(1);
            AtomicBoolean receivedHeaders = new AtomicBoolean();
            var http = new PlatformHttpClient(properties, mock(PlatformRateLimiter.class), request -> {
                URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + request.uri().getPath());
                var response = client.send(HttpRequest.newBuilder(local).timeout(Duration.ofSeconds(1)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                receivedHeaders.set(true);
                return new PlatformHttpClient.Raw(response.statusCode(), response.headers(), response.body());
            });
            try {
                assertThatThrownBy(() -> http.get(Platform.NOWCODER, "https://www.nowcoder.com/stall"))
                        .isInstanceOfSatisfying(ApiException.class, e -> {
                            assertThat(e.getKind()).isEqualTo(ErrorKind.NETWORK);
                            assertThat(e.getMessage()).contains("请求超时");
                        });
                assertThat(receivedHeaders).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(http.get(Platform.NOWCODER, "https://www.nowcoder.com/ok")).isEqualTo("ok");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
