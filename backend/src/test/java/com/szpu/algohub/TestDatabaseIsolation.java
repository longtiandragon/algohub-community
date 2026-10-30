package com.szpu.algohub;

import java.net.URI;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Fail before bean creation: integration tests must never connect to a non-test database. */
public final class TestDatabaseIsolation implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        if (Set.of(env.getActiveProfiles()).contains("prod"))
            throw new IllegalStateException("Tests cannot use the prod profile");
        String url = env.getRequiredProperty("spring.datasource.url");
        checkDatabase(url);
        checkDatabase(env.getProperty("spring.flyway.url", url));
        String host = env.getProperty("spring.data.redis.host", "localhost");
        if (!local(host) || env.getProperty("spring.data.redis.url") != null
                || env.getProperty("spring.data.redis.database", Integer.class, 0) == 0)
            throw new IllegalStateException("Tests require a loopback Redis host and a nonzero database; URL overrides are forbidden");
    }
    static void checkDatabase(String url) {
        if (!url.startsWith("jdbc:postgresql://"))
            throw new IllegalStateException("Tests require an explicit PostgreSQL test URL");
        URI uri = URI.create(url.substring(5));
        if (!local(uri.getHost()) || uri.getPath() == null || !uri.getPath().endsWith("_test")
                || uri.getUserInfo() != null || uri.getQuery() != null)
            throw new IllegalStateException("Tests require a loopback PostgreSQL database ending in _test, without URL overrides");
    }
    private static boolean local(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
    }
}
