package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.config.AuthProperties;
import com.arthur.refineloop.data.entity.SysUserEntity;
import com.arthur.refineloop.data.mapper.SysUserMapper;
import com.arthur.refineloop.data.mapper.SysUserSessionMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.model.UserUpdateSpec;
import com.arthur.refineloop.user.model.WatchlistSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 账号用例：唯一性、密码策略、管理员保留规则、删除清理、重置密码撤销会话。
 *
 * <p>全部用真实 SQLite（与生产同引擎），密码走真实 PBKDF2。
 */
class UserServiceTest {

    private JdbcTemplate jdbc;
    private UserService userService;
    private UserPreferenceService preferenceService;
    private UserWatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T09:00:00Z");
        AuthProperties properties = new AuthProperties(true, "admin", "admin123", 7, 8);
        PasswordHasher hasher = new PasswordHasher();
        SysUserMapper userMapper = SchemaTestSupport.mapper(jdbc, SysUserMapper.class);
        SysUserSessionMapper sessionMapper = SchemaTestSupport.mapper(jdbc, SysUserSessionMapper.class);
        UserPreferenceMapper preferenceMapper = SchemaTestSupport.mapper(jdbc, UserPreferenceMapper.class);
        UserWatchlistMapper watchlistMapper = SchemaTestSupport.mapper(jdbc, UserWatchlistMapper.class);
        userService = new UserService(userMapper, sessionMapper, preferenceMapper, watchlistMapper,
                hasher, properties, clock);
        preferenceService = new UserPreferenceService(preferenceMapper, clock);
        watchlistService = new UserWatchlistService(watchlistMapper,
                SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class), clock);
    }

    @Test
    @DisplayName("创建后可查询：密码只落哈希、默认启用、角色默认 USER")
    void createAndGet() {
        UserViews.UserDetail created = userService.create(
                new UserCreateSpec("alice", "Alice@123", null, null, "首个普通用户"));

        assertThat(created.id()).isPositive();
        assertThat(created.username()).isEqualTo("alice");
        assertThat(created.displayName()).isEqualTo("alice");
        assertThat(created.role()).isEqualTo(UserRole.USER.code());
        assertThat(created.active()).isTrue();
        assertThat(created.permissions()).containsExactly("data:read");

        SysUserEntity entity = userService.findByUsername("alice").orElseThrow();
        assertThat(entity.getPasswordHash()).startsWith("pbkdf2$").doesNotContain("Alice@123");
        assertThat(userService.get(created.id()).username()).isEqualTo("alice");
    }

    @Test
    @DisplayName("用户名重复 → 409")
    void duplicateUsernameConflicts() {
        userService.create(new UserCreateSpec("bob", "Bob@12345", null, null, null));

        ApiException error = catchThrowableOfType(
                () -> userService.create(new UserCreateSpec("bob", "Bob@12345", null, null, null)),
                ApiException.class);
        assertThat(error.getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("密码过短/过长/空 → 400")
    void passwordPolicyIsEnforced() {
        assertThat(catchThrowableOfType(
                () -> userService.create(new UserCreateSpec("short", "123", null, null, null)),
                ApiException.class).getCode()).isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> userService.create(new UserCreateSpec("empty", " ", null, null, null)),
                ApiException.class).getCode()).isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> userService.create(new UserCreateSpec("long", "x".repeat(65), null, null, null)),
                ApiException.class).getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("非法角色 → 400（@EnumCode 之外的兜底）")
    void invalidRoleIsRejected() {
        assertThat(catchThrowableOfType(
                () -> userService.create(new UserCreateSpec("carol", "Carol@123", null, "ROOT", null)),
                ApiException.class).getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("分页与关键过滤：keyword 命中用户名/显示名，role/active 可过滤")
    void pageFiltersByKeywordRoleAndActive() {
        userService.create(new UserCreateSpec("dave", "Dave@1234", "大卫", UserRole.ADMIN.code(), null));
        userService.create(new UserCreateSpec("erin", "Erin@1234", "艾琳", UserRole.USER.code(), null));

        PageResult<UserViews.UserSummary> all = userService.page(null, null, null, PageQuery.of(1, 20));
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).extracting(UserViews.UserSummary::username)
                .containsExactlyInAnyOrder("dave", "erin");

        assertThat(userService.page("艾琳", null, null, PageQuery.of(1, 20)).items())
                .extracting(UserViews.UserSummary::username).containsExactly("erin");
        assertThat(userService.page(null, UserRole.ADMIN.code(), null, PageQuery.of(1, 20)).items())
                .extracting(UserViews.UserSummary::username).containsExactly("dave");
        assertThat(userService.page(null, null, false, PageQuery.of(1, 20)).total()).isZero();
    }

    @Test
    @DisplayName("不能删除当前登录账号；不能删除/降级最后一个管理员")
    void adminInvariantsAreEnforced() {
        UserViews.UserDetail admin = userService.create(
                new UserCreateSpec("root", "Root@1234", null, UserRole.ADMIN.code(), null));
        UserViews.UserDetail user = userService.create(
                new UserCreateSpec("viewer", "Viewer@123", null, UserRole.USER.code(), null));

        assertThat(catchThrowableOfType(
                () -> userService.delete(admin.id(), admin.id()), ApiException.class).getCode()).isEqualTo(1004);
        assertThat(catchThrowableOfType(
                () -> userService.update(admin.id(), new UserUpdateSpec(null, UserRole.USER.code(), null, null)),
                ApiException.class).getCode()).isEqualTo(1004);
        assertThat(catchThrowableOfType(
                () -> userService.update(admin.id(), new UserUpdateSpec(null, null, false, null)),
                ApiException.class).getCode()).isEqualTo(1004);

        // 普通用户可以删
        userService.delete(user.id(), admin.id());
        assertThat(userService.findByUsername("viewer")).isEmpty();
    }

    @Test
    @DisplayName("删除用户会清理其会话/偏好/自选股（无外键，服务层负责）")
    void deleteCleansUpOwnedData() {
        UserViews.UserDetail admin = userService.create(
                new UserCreateSpec("root", "Root@1234", null, UserRole.ADMIN.code(), null));
        UserViews.UserDetail target = userService.create(
                new UserCreateSpec("ghost", "Ghost@123", null, UserRole.USER.code(), null));
        preferenceService.replace(target.id(), Map.of("trading.style", "SWING"));
        watchlistService.create(target.id(), new WatchlistSpec("000001.SZ", "平安银行", null, null, 0));

        userService.delete(target.id(), admin.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_preference WHERE user_id = ?",
                Long.class, target.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_watchlist WHERE user_id = ?",
                Long.class, target.id())).isZero();
    }

    @Test
    @DisplayName("重置密码：旧密码失效、原会话全部撤销")
    void resetPasswordRevokesSessions() {
        UserViews.UserDetail user = userService.create(
                new UserCreateSpec("frank", "Frank@123", null, UserRole.USER.code(), null));
        AuthProperties properties = new AuthProperties(true, "admin", "admin123", 7, 8);
        PasswordHasher hasher = new PasswordHasher();
        SysUserMapper userMapper = SchemaTestSupport.mapper(jdbc, SysUserMapper.class);
        SysUserSessionMapper sessionMapper = SchemaTestSupport.mapper(jdbc, SysUserSessionMapper.class);
        AuthService authService = new AuthService(userMapper, sessionMapper, hasher, properties,
                MutableClock.at("2026-11-24T09:00:00Z"));
        String token = authService.login("frank", "Frank@123").token();

        userService.resetPassword(user.id(), "Frank@456");

        assertThat(authService.authenticate(token)).isEmpty();
        assertThat(catchThrowableOfType(() -> authService.login("frank", "Frank@123"), ApiException.class)
                .getCode()).isEqualTo(401);
        assertThat(authService.login("frank", "Frank@456").token()).isNotBlank();
    }

    @Test
    @DisplayName("改密：原密码错误 → 400；新旧相同 → 1004")
    void changePasswordValidatesOldPassword() {
        UserViews.UserDetail user = userService.create(
                new UserCreateSpec("grace", "Grace@123", null, UserRole.USER.code(), null));

        assertThat(catchThrowableOfType(
                () -> userService.changePassword(user.id(), "wrong", "Grace@456"),
                ApiException.class).getCode()).isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> userService.changePassword(user.id(), "Grace@123", "Grace@123"),
                ApiException.class).getCode()).isEqualTo(1004);
        userService.changePassword(user.id(), "Grace@123", "Grace@456");
    }

    @Test
    @DisplayName("不存在的用户 → 404")
    void unknownUserIsNotFound() {
        assertThat(catchThrowableOfType(() -> userService.get(9999L), ApiException.class).getCode())
                .isEqualTo(404);
    }
}
