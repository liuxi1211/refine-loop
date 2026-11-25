package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.config.AuthProperties;
import com.arthur.refineloop.data.entity.SysUserEntity;
import com.arthur.refineloop.data.mapper.SysUserMapper;
import com.arthur.refineloop.data.mapper.SysUserSessionMapper;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.AuthenticatedUser;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.model.UserUpdateSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 认证用例（真实 SQLite + PBKDF2 + 可推进时钟）。
 *
 * <p>覆盖：令牌签发/还原、错误密码 401、停用账号 403、过期、登出撤销、改密撤销全部会话。
 */
class AuthServiceTest {

    private static final String USERNAME = "trader";
    private static final String PASSWORD = "Trader@123";

    private JdbcTemplate jdbc;
    private MutableClock clock;
    private AuthService authService;
    private UserService userService;
    private SysUserMapper userMapper;
    private long userId;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        clock = MutableClock.at("2026-11-24T09:00:00Z");
        AuthProperties properties = new AuthProperties(true, "admin", "admin123", 7, 8);
        PasswordHasher hasher = new PasswordHasher();
        userMapper = SchemaTestSupport.mapper(jdbc, SysUserMapper.class);
        SysUserSessionMapper sessionMapper = SchemaTestSupport.mapper(jdbc, SysUserSessionMapper.class);
        authService = new AuthService(userMapper, sessionMapper, hasher, properties, clock);
        userService = new UserService(userMapper, sessionMapper,
                SchemaTestSupport.mapper(jdbc, UserPreferenceMapper.class),
                SchemaTestSupport.mapper(jdbc, UserWatchlistMapper.class),
                hasher, properties, clock);
        UserViews.UserDetail created = userService.create(
                new UserCreateSpec(USERNAME, PASSWORD, "交易员", UserRole.ADMIN.code(), null));
        userId = created.id();
    }

    @Test
    @DisplayName("登录签发令牌：可还原身份、记录最近登录时间、有效期 = 配置天数")
    void loginIssuesTokenAndAuthenticates() {
        UserViews.LoginResult result = authService.login(USERNAME, PASSWORD);

        assertThat(result.token()).isNotBlank();
        assertThat(result.user().username()).isEqualTo(USERNAME);
        assertThat(result.user().permissions()).contains("user:write");
        assertThat(result.expiresAt())
                .isEqualTo(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).plusDays(7));

        Optional<AuthenticatedUser> authenticated = authService.authenticate(result.token());
        assertThat(authenticated).isPresent();
        assertThat(authenticated.get().id()).isEqualTo(userId);
        assertThat(authenticated.get().has(UserRole.ADMIN.permissions().iterator().next())).isTrue();

        SysUserEntity user = userMapper.selectById(userId);
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("密码错误 → 401；用户名不存在 → 401（不区分，避免枚举账号）")
    void loginRejectsBadCredentials() {
        assertThat(catchThrowableOfType(() -> authService.login(USERNAME, "wrong-password"), ApiException.class)
                .getCode()).isEqualTo(401);
        assertThat(catchThrowableOfType(() -> authService.login("nobody", PASSWORD), ApiException.class)
                .getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("停用账号登录 → 403")
    void loginRejectsDisabledAccount() {
        // 停用自己会触发「至少保留一个管理员」，先建一个备用管理员
        userService.create(new UserCreateSpec("backup", "Backup@123", "备用管理员",
                UserRole.ADMIN.code(), null));
        userService.update(userId, new UserUpdateSpec(null, null, false, null));

        assertThat(catchThrowableOfType(() -> authService.login(USERNAME, PASSWORD), ApiException.class)
                .getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("令牌过期或已撤销 → 认证为空")
    void expiredOrRevokedTokenIsRejected() {
        UserViews.LoginResult result = authService.login(USERNAME, PASSWORD);
        assertThat(authService.authenticate(result.token())).isPresent();

        clock.advance(ChronoUnit.DAYS.getDuration().toMillis() * 8);
        assertThat(authService.authenticate(result.token())).isEmpty();

        String fresh = authService.login(USERNAME, PASSWORD).token();
        authService.logout(fresh);
        assertThat(authService.authenticate(fresh)).isEmpty();
    }

    @Test
    @DisplayName("改密后旧令牌全部失效（需要重新登录）")
    void passwordChangeRevokesAllSessions() {
        String token = authService.login(USERNAME, PASSWORD).token();
        assertThat(authService.authenticate(token)).isPresent();

        userService.changePassword(userId, PASSWORD, "Trader@456");

        assertThat(authService.authenticate(token)).isEmpty();
        assertThat(catchThrowableOfType(() -> authService.login(USERNAME, PASSWORD), ApiException.class)
                .getCode()).isEqualTo(401);
        assertThat(authService.login(USERNAME, "Trader@456").token()).isNotBlank();
    }

    @Test
    @DisplayName("停用账号后已签发令牌立即失效")
    void disablingAccountInvalidatesIssuedToken() {
        String token = authService.login(USERNAME, PASSWORD).token();
        assertThat(authService.authenticate(token)).isPresent();

        // 停用自己会触发「至少保留一个管理员」，先提升一个备用管理员再停用
        userService.create(new UserCreateSpec("backup", "Backup@123", "备用管理员",
                UserRole.ADMIN.code(), null));
        userService.update(userId, new UserUpdateSpec(null, null, false, null));

        assertThat(authService.authenticate(token)).isEmpty();
    }

    @Test
    @DisplayName("空白令牌直接返回空（不查库）")
    void blankTokenIsEmpty() {
        assertThat(authService.authenticate(null)).isEmpty();
        assertThat(authService.authenticate("  ")).isEmpty();
    }
}
