package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
    "algohub.security.rate-limit-scale=1"})
class TrustedProxyTest {
    @LocalServerPort int port;
    @Autowired ServletWebServerApplicationContext context;
    @Autowired AuthRateLimit limits;
    @Autowired StringRedisTemplate redis;

    @Test void onlyTrustedProxyCanSupplyClientIp() throws Exception {
        redis.keys("algohub:rate:*").forEach(redis::delete);
        for(int i=0;i<120;i++) limits.check("auth:ip","203.0.113.123",120,60);
        var server = (TomcatWebServer)context.getWebServer();
        RemoteIpValve valve = java.util.Arrays.stream(server.getTomcat().getEngine().getPipeline().getValves())
                .filter(v->v instanceof RemoteIpValve).map(v->(RemoteIpValve)v).findFirst().orElseThrow();
        String trusted = valve.getInternalProxies();
        try {
            assertThat(request()).isEqualTo(429); // 回环代理传入真实客户端地址
            valve.setInternalProxies("192\\.0\\.2\\.1"); // 不信任当前连接
            assertThat(request()).isEqualTo(401); // 伪造头被忽略，按 127.0.0.1 计数
        } finally { valve.setInternalProxies(trusted); }
    }
    private int request() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .header("X-Forwarded-For","203.0.113.123").header("X-Forwarded-Proto","https")
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"proxy-no-such-user\",\"password\":\"invalid-password\"}"))
                .build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
