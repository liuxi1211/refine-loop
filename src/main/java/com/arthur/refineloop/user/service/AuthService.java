package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.config.AuthProperties;
import com.arthur.refineloop.data.entity.SysUserEntity;
import com.arthur.refineloop.data.entity.SysUserSessionEntity;
import com.arthur.refineloop.data.mapper.SysUserMapper;
import com.arthur.refineloop.data.mapper.SysUserSessionMapper;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.AuthenticatedUser;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 认证用例：登录签发不透明令牌、按令牌还原身份、登出撤销。
 *
 * <p>为什么用「不透明随机令牌 + 会话表」而不是 JWT：单服务器单体应用不需要跨服务验签，
 * 而会话表能立刻撤销（登出/改密/停用账号），JWT 只能等过期。对外契约仍是
 * {@code Authorization: Bearer <token>}（规范 04 §8），令牌格式是实现细节。
 *
 * <p>不变量：数据库只存令牌的 SHA-256 十六进制哈希；令牌绝对有效期 = 签发时间 + 配置天数，
 * 不做滑动续期（避免长期未使用会话永久有效）；每次请求最多每
 * {@link #TOUCH_INTERVAL} 更新一次最近使用时间，避免 SQLite 每请求一写。
 *
 * <p>依赖方向：{@code data}（两个 Mapper）+ {@code common} + {@code config.AuthProperties}。
 */
@Service
public class AuthService {

    private static final int TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SysUserMapper userMapper;
    private final SysUserSessionMapper sessionMapper;
    private final PasswordHasher passwordHasher;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(SysUserMapper userMapper, SysUserSessionMapper sessionMapper,
                       PasswordHasher passwordHasher, AuthProperties authProperties, Clock clock) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordHasher = passwordHasher;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /**
     * 登录：校验用户名/密码/启用状态，成功后签发令牌并记录最近登录时间。
     *
     * @throws ApiException 401（用户名或密码错误）、403（账号已停用）
     */
    @Transactional
    public UserViews.LoginResult login(String username, String rawPassword) {
        String normalizedUsername = Asserts.notBlank(username, "username");
        String password = Asserts.notBlank(rawPassword, "password");
        SysUserEntity user = userMapper.selectOne(Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getUsername, normalizedUsername.trim())
                .last("LIMIT 1"));
        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw ApiException.forbidden("账号已停用，请联系管理员");
        }

        LocalDateTime now = now();
        String rawToken = newToken();
        SysUserSessionEntity session = new SysUserSessionEntity();
        session.setUserId(user.getId());
        session.setTokenHash(hashToken(rawToken));
        session.setIssuedAt(now);
        session.setExpiresAt(now.plusDays(authProperties.tokenTtlDays()));
        session.setLastUsedAt(now);
        session.setRevoked(false);
        session.touch(now);
        sessionMapper.insert(session);

        user.setLastLoginAt(now);
        user.touch(now);
        userMapper.updateById(user);
        return new UserViews.LoginResult(rawToken, session.getExpiresAt(), toCurrentUser(user));
    }

    /** 按明文令牌还原身份；令牌不存在/已撤销/已过期/账号停用均返回 empty。 */
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        if (Strings.isBlank(rawToken)) {
            return Optional.empty();
        }
        String tokenHash = hashToken(rawToken.trim());
        SysUserSessionEntity session = sessionMapper.selectOne(Wrappers.<SysUserSessionEntity>lambdaQuery()
                .eq(SysUserSessionEntity::getTokenHash, tokenHash)
                .last("LIMIT 1"));
        if (session == null || Boolean.TRUE.equals(session.getRevoked())) {
            return Optional.empty();
        }
        LocalDateTime now = now();
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        SysUserEntity user = userMapper.selectById(session.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            return Optional.empty();
        }
        if (session.getLastUsedAt() == null
                || session.getLastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            sessionMapper.touch(tokenHash, now);
        }
        return Optional.of(toAuthenticatedUser(user));
    }

    /** 登出：撤销该令牌（幂等，令牌不存在也视为成功）。 */
    public void logout(String rawToken) {
        if (Strings.isBlank(rawToken)) {
            return;
        }
        sessionMapper.revokeByTokenHash(hashToken(rawToken.trim()), now());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 令牌哈希：SHA-256 十六进制（64 字符），与 {@code sys_user_session.token_hash} 宽度一致。 */
    private static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法完成令牌哈希", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static AuthenticatedUser toAuthenticatedUser(SysUserEntity user) {
        UserRole role = UserRole.fromCode(user.getRole());
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(),
                role, role.permissions());
    }

    private static UserViews.CurrentUser toCurrentUser(SysUserEntity user) {
        UserRole role = UserRole.fromCode(user.getRole());
        return new UserViews.CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                role.code(), role.label(), role.permissions().stream().map(Permission::code).sorted().toList());
    }
}
