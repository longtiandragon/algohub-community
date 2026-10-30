package com.szpu.algohub.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 访问令牌（JWT）的签发与校验。
 *
 * 设计取舍：访问令牌短时效且无状态，服务端不逐个存库；需要立刻失效时有两条通道：
 * 1. 登出 / 异常时把该令牌的 jti 写入 Redis 黑名单，直到其自然过期；
 * 2. 改密或“退出所有设备”时推进用户的 tokens_valid_from，签发时间早于它的令牌一律失效。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "szpu-algohub";

    private final SecretKey key;
    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.key = loadOrCreateKey(properties.getSecurity());
    }

    private SecretKey loadOrCreateKey(AppProperties.Security config) {
        try {
            if (StringUtils.hasText(config.getJwtSecret())) {
                byte[] raw = decode(config.getJwtSecret());
                if (raw.length < 32) {
                    throw new IllegalStateException("JWT 密钥至少需要 32 字节");
                }
                return Keys.hmacShaKeyFor(raw);
            }
            Path path = Path.of(config.getJwtSecretFile());
            if (config.isRequireExistingKeys() && (!path.isAbsolute() || !Files.isRegularFile(path))) {
                throw new IllegalStateException("生产 JWT 密钥必须是已存在的绝对路径文件，禁止自动生成");
            }
            if (Files.exists(path)) {
                return Keys.hmacShaKeyFor(Base64.getDecoder()
                        .decode(Files.readString(path, StandardCharsets.UTF_8).trim()));
            }
            byte[] raw = new byte[64];
            new SecureRandom().nextBytes(raw);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            log.warn("已生成 JWT 签名密钥: {}（请勿提交到版本库；更换密钥会使所有登录态失效）", path.toAbsolutePath());
            return Keys.hmacShaKeyFor(raw);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 JWT 签名密钥", e);
        }
    }

    private byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            return secret.trim().getBytes(StandardCharsets.UTF_8);
        }
    }

    public Duration accessTokenTtl() {
        return Duration.ofMinutes(properties.getSecurity().getAccessTokenMinutes());
    }

    public record IssuedToken(String token, String jti, Instant issuedAt, Instant expiresAt) {
    }

    public IssuedToken issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        // Database defaults can be slightly ahead of this JVM clock. A token issued
        // for this exact identity version must not predate that version's creation.
        if (user.getTokensValidFrom() != null && now.isBefore(user.getTokensValidFrom())) {
            now = user.getTokensValidFrom();
        }
        Instant expiry = now.plus(accessTokenTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .id(jti)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(expiry))
                .claims(Map.of(
                        "username", user.getUsername(),
                        "role", user.getRole(),
                        "identityVersion", user.getTokensValidFrom()==null ? "" : user.getTokensValidFrom().toString(),
                        "displayName", user.getDisplayName() == null ? "" : user.getDisplayName()))
                .signWith(key)
                .compact();
        return new IssuedToken(token, jti, now, expiry);
    }

    /** 解析并校验签名与有效期；失败统一按未认证处理，不向调用方泄露细节。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "登录状态无效或已过期，请重新登录");
        }
    }
}
