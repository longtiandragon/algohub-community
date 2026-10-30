package com.szpu.algohub.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

/**
 * 登录态加密存储。密钥保存在数据库之外的文件中；日志与接口不回显明文。
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(AppProperties properties) {
        this.key = loadOrCreateKey(properties.getCredential(), properties.getSecurity().isRequireExistingKeys());
    }

    private SecretKey loadOrCreateKey(AppProperties.Credential config, boolean requireExisting) {
        try {
            if (StringUtils.hasText(config.getMasterKey())) {
                return new SecretKeySpec(Base64.getDecoder().decode(config.getMasterKey().trim()), "AES");
            }
            Path path = Path.of(config.getKeyFile());
            if (requireExisting && (!path.isAbsolute() || !Files.isRegularFile(path))) {
                throw new IllegalStateException("生产凭据密钥必须是已存在的绝对路径文件，禁止自动生成；迁移时请复制原密钥");
            }
            if (Files.exists(path)) {
                String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
                return new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
            }
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            log.warn("已生成平台登录态加密密钥: {}（请勿提交到版本库，备份时与数据库分开保存）", path.toAbsolutePath());
            return new SecretKeySpec(raw, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化凭据加密密钥", e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new ApiException(ErrorKind.INTERNAL, "凭据加密失败");
        }
    }

    public String decrypt(String cipherText) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "凭据解密失败，可能密钥已更换，请重新授权");
        }
    }
}
