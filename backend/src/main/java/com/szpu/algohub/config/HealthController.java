package com.szpu.algohub.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** 仅供同机探针；Nginx 不向公网转发 /internal/。 */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }
    @GetMapping("/internal/health")
    public ResponseEntity<Map<String,String>> health() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            if (!"PONG".equals(pong)) throw new IllegalStateException();
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException e) { return ResponseEntity.status(503).body(Map.of("status", "DOWN")); }
    }
}
