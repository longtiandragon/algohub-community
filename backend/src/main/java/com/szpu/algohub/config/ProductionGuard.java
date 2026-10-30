package com.szpu.algohub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.util.Arrays;

/** 生产启动配置校验；demo 清库配置另外使用 !prod 限制。 */
@Configuration
@Profile("prod")
public class ProductionGuard implements InitializingBean {
    private final Environment env;
    public ProductionGuard(Environment env) { this.env = env; }
    @org.springframework.context.annotation.Bean
    org.springframework.boot.ApplicationRunner verifyAdministratorPasswords(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.security.crypto.password.PasswordEncoder encoder) {
        return args -> {
            for (String hash : jdbc.queryForList("select password_hash from app_user where role='ADMIN' and password_hash is not null", String.class)) {
                if (encoder.matches("admin12345", hash))
                    throw new IllegalStateException("数据库中存在使用默认密码的管理员；请在迁移前改密");
            }
        };
    }
    @Override public void afterPropertiesSet() {
        if (Arrays.asList(env.getActiveProfiles()).contains("demo"))
            throw new IllegalStateException("prod 不能与 demo 同时启用");
        if (!env.getProperty("algohub.security.production", Boolean.class, false)
                || !env.getProperty("algohub.security.secure-cookie", Boolean.class, false)
                || !env.getProperty("algohub.security.require-existing-keys", Boolean.class, false))
            throw new IllegalStateException("prod 禁止关闭生产安全校验");
        String password = env.getRequiredProperty("spring.datasource.password");
        if (unsafePassword(password, 16))
            throw new IllegalStateException("生产数据库密码必须至少 16 位且不能使用开发密码");
        String migrationPassword = env.getProperty("spring.flyway.password", password);
        if (unsafePassword(migrationPassword, 16))
            throw new IllegalStateException("生产数据库迁移密码必须至少 16 位且不能使用开发密码");
        if (unsafePassword(env.getRequiredProperty("spring.data.redis.password"), 16))
            throw new IllegalStateException("生产 Redis 密码必须至少 16 位");
        String adminPassword = env.getProperty("algohub.bootstrap.admin-password", "");
        if (!adminPassword.isEmpty() && unsafePassword(adminPassword, 10))
            throw new IllegalStateException("生产管理员密码不能使用示例占位值或默认密码");
        URI origin = URI.create(env.getRequiredProperty("PUBLIC_ORIGIN"));
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isEmpty()))
            throw new IllegalStateException("PUBLIC_ORIGIN 应为 https://正式域名，不带末尾斜杠或路径");
    }

    private static boolean unsafePassword(String value, int minimum) {
        if (value == null || value.isBlank() || value.length() < minimum) return true;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("replace_with") || lower.contains("change-this")
                || lower.contains("changeme") || lower.contains("admin12345")
                || lower.equals("algohub_local_dev");
    }
}
