package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.szpu.algohub.config.ProductionGuard;

class ProductionConfigTest {
    @Test void rejectsTemplatePasswordsForEveryProductionCredential() {
        for (String key : java.util.List.of("spring.datasource.password", "spring.flyway.password",
                "spring.data.redis.password", "algohub.bootstrap.admin-password")) {
            for (String value : java.util.List.of("REPLACE_WITH_RANDOM_LOCAL_PASSWORD", "change-this-password", "algohub_local_dev")) {
                assertThatThrownBy(() -> new ProductionGuard(environment().withProperty(key, value)).afterPropertiesSet())
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }
    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("algohub.security.production","true")
                .withProperty("algohub.security.secure-cookie","true")
                .withProperty("algohub.security.require-existing-keys","true")
                .withProperty("spring.datasource.password","random-database-password-328")
                .withProperty("spring.data.redis.password","random-redis-password-745")
                .withProperty("PUBLIC_ORIGIN","https://algo.example.com");
    }
    @Test void rejectsDemoProfileAlongsideProduction() {
        var env = environment(); env.setActiveProfiles("prod","demo");
        assertThatThrownBy(()->new ProductionGuard(env).afterPropertiesSet())
                .hasMessageContaining("demo");
    }
    @Test void rejectsInsecureCookieOverride() {
        var env = environment().withProperty("algohub.security.secure-cookie","false");
        assertThatThrownBy(()->new ProductionGuard(env).afterPropertiesSet())
                .hasMessageContaining("安全校验");
    }
    @Test void rejectsDevelopmentDatabasePassword() {
        var env = environment().withProperty("spring.datasource.password","algohub_local_dev");
        assertThatThrownBy(()->new ProductionGuard(env).afterPropertiesSet())
                .hasMessageContaining("数据库密码");
    }
    @Test void rejectsWeakMigrationPasswordEvenWithStrongIndependentRuntimePassword() {
        for (String migrationPassword : java.util.List.of("short", "", "algohub_local_dev")) {
            var env = environment().withProperty("spring.datasource.username", "algohub_app")
                    .withProperty("spring.datasource.password", "synthetic-strong-runtime-password")
                    .withProperty("spring.flyway.password", migrationPassword);
            assertThatThrownBy(() -> new ProductionGuard(env).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("数据库迁移密码");
        }
    }
    @Test void acceptsSeparateStrongRuntimeAndMigrationPasswords() {
        var env = environment().withProperty("spring.datasource.username", "algohub_app")
                .withProperty("spring.datasource.password", "synthetic-strong-runtime-password")
                .withProperty("spring.flyway.password", "synthetic-strong-migration-password");
        assertThatCode(() -> new ProductionGuard(env).afterPropertiesSet()).doesNotThrowAnyException();
    }
    @Test void omittedMigrationPasswordKeepsRuntimePasswordFallbackCompatible() {
        var env = environment().withProperty("spring.datasource.username", "algohub_app");
        assertThatCode(() -> new ProductionGuard(env).afterPropertiesSet()).doesNotThrowAnyException();
        env.withProperty("spring.datasource.password", "short");
        assertThatThrownBy(() -> new ProductionGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("数据库密码");
    }
    @Test void requiresHttpsOriginAndAcceptsValidSettings() {
        assertThatCode(()->new ProductionGuard(environment()).afterPropertiesSet()).doesNotThrowAnyException();
        for(String origin : java.util.List.of("http://algo.example.com","https://algo.example.com/", "https://u:p@algo.example.com")) {
            assertThatThrownBy(()->new ProductionGuard(environment().withProperty("PUBLIC_ORIGIN",origin)).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
