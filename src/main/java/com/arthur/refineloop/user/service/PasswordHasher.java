package com.arthur.refineloop.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256 + 每用户独立随机盐，编码串自描述（算法/迭代次数/盐/哈希）。
 *
 * <p>为什么不用现成库：Spring Security 的 {@code BCryptPasswordEncoder} 需要引入整个
 * {@code spring-security-crypto}，而 PBKDF2 在 JDK 内即可完成，且编码串可读、可平滑升级迭代次数。
 *
 * <p>存储格式：{@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}。校验用
 * {@link MessageDigest#isEqual} 做常量时间比较，避免时序侧信道。
 *
 * <p>依赖：仅 JDK；无状态，可在并发请求间共享。
 */
@Component
public class PasswordHasher {

    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final String SEPARATOR = "$";
    /** 迭代次数：兼顾本地登录延迟（约 50~100ms）与离线爆破成本。 */
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 生成自描述编码串（每次调用都换新盐）。 */
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + SEPARATOR + ITERATIONS + SEPARATOR
                + Base64.getEncoder().encodeToString(salt) + SEPARATOR
                + Base64.getEncoder().encodeToString(hash);
    }

    /** 校验明文密码；编码串损坏或算法不匹配时返回 false（不抛异常，避免登录 500）。 */
    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || rawPassword.isEmpty() || encoded == null || encoded.isBlank()) {
            return false;
        }
        String[] parts = encoded.split("\\" + SEPARATOR);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            log.warn("存储的密码哈希格式非法（已拒绝本次登录），请管理员重置该账号密码");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            log.warn("存储的密码哈希无法解码（已拒绝本次登录）: {}", e.getMessage());
            return false;
        }
    }

    private static byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JDK 不支持 " + ALGORITHM + "，无法完成密码哈希", e);
        } finally {
            spec.clearPassword();
        }
    }
}
