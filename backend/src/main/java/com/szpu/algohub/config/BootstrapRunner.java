package com.szpu.algohub.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;

/**
 * 首次启动时创建本地管理员账号，便于验证入校审核与管理后台流程。
 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppProperties properties;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public BootstrapRunner(AppProperties properties, AppUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getBootstrap().getAdminUsername();
        AppUser existing = userMapper.findByUsername(username);
        if (properties.getSecurity().isProduction()) {
            if (!properties.getSecurity().isSecureCookie() || !properties.getSecurity().isRequireExistingKeys()) {
                throw new IllegalStateException("生产环境必须启用 Secure Cookie 与现有密钥校验");
            }
            if (existing != null && existing.getPasswordHash() != null
                    && passwordEncoder.matches("admin12345", existing.getPasswordHash())) {
                throw new IllegalStateException("已有管理员仍使用默认密码；请在迁移前修改密码，配置不会覆盖已有密码");
            }
            if (existing == null) {
                String password = properties.getBootstrap().getAdminPassword();
                if (password == null || password.length() < 10 || password.length() > 64
                        || password.equalsIgnoreCase("change-this-password")
                        || password.toLowerCase(java.util.Locale.ROOT).contains("admin12345")) {
                    throw new IllegalStateException("首次生产启动须设置 10–64 位 ADMIN_PASSWORD");
                }
            }
        }
        if (existing != null) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(properties.getBootstrap().getAdminPassword()));
        admin.setDisplayName(properties.getBootstrap().getAdminDisplayName());
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        userMapper.insert(admin);
        log.warn("已创建本地管理员账号 {}，请登录后立即修改密码。", username);
    }
}
