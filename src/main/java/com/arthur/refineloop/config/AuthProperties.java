package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号与登录配置（前缀 {@code refine-loop.auth}）。
 *
 * <p>由启动类 {@code @ConfigurationPropertiesScan} 注册；装配见 {@code web.auth}（拦截器）与
 * {@code user.service.AuthService}（令牌有效期）。
 *
 * <p>安全约定：
 * <ul>
 *   <li>{@code bootstrap-password} 只在「库中还没有任何用户」时生效，用于首次登录；
 *       生产必须用环境变量 {@code REFINE_LOOP_BOOTSTRAP_PASSWORD} 覆盖，并在首次登录后立即改密；</li>
 *   <li>{@code token-ttl-days} 是登录令牌的绝对有效期（到期需重新登录，不做滑动续期）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "refine-loop.auth")
public record AuthProperties(
        Boolean bootstrapEnabled,
        String bootstrapUsername,
        String bootstrapPassword,
        Integer tokenTtlDays,
        Integer passwordMinLength) {

    public AuthProperties {
        bootstrapEnabled = bootstrapEnabled == null ? Boolean.TRUE : bootstrapEnabled;
        bootstrapUsername = bootstrapUsername == null || bootstrapUsername.isBlank()
                ? "admin" : bootstrapUsername.trim();
        bootstrapPassword = bootstrapPassword == null || bootstrapPassword.isBlank() ? "admin123" : bootstrapPassword;
        tokenTtlDays = tokenTtlDays == null || tokenTtlDays < 1 ? 7 : tokenTtlDays;
        passwordMinLength = passwordMinLength == null || passwordMinLength < 6 ? 8 : passwordMinLength;
    }
}
