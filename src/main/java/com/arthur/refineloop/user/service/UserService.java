package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.error.CommonErrorCode;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.common.page.PageResults;
import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.common.util.Enums;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.config.AuthProperties;
import com.arthur.refineloop.data.entity.SysUserEntity;
import com.arthur.refineloop.data.entity.UserPreferenceEntity;
import com.arthur.refineloop.data.entity.UserWatchlistEntity;
import com.arthur.refineloop.data.mapper.SysUserMapper;
import com.arthur.refineloop.data.mapper.SysUserSessionMapper;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.model.UserUpdateSpec;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 用户账号用例：分页查询、创建、修改、删除、重置密码、改密。
 *
 * <p>依赖方向：{@code data}（四个 Mapper）+ {@code common} + {@code config.AuthProperties}；
 * 向 {@code web} 输出 {@link UserViews}，不依赖 web。
 *
 * <p>不变量：
 * <ul>
 *   <li>用户名唯一（数据库 {@code uk_sys_user} 兜底，服务层先给出 409 可读文案）；</li>
 *   <li>系统至少保留一个启用状态的管理员（删/降级/停用最后一个管理员 → 1004）；</li>
 *   <li>密码只以 {@link PasswordHasher} 编码串落库；改密/重置/停用/删除都会撤销该用户全部会话。</li>
 * </ul>
 */
@Service
public class UserService {

    private static final int PASSWORD_MAX_LENGTH = 64;

    private final SysUserMapper userMapper;
    private final SysUserSessionMapper sessionMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final UserWatchlistMapper watchlistMapper;
    private final PasswordHasher passwordHasher;
    private final AuthProperties authProperties;
    private final Clock clock;

    public UserService(SysUserMapper userMapper, SysUserSessionMapper sessionMapper,
                       UserPreferenceMapper preferenceMapper, UserWatchlistMapper watchlistMapper,
                       PasswordHasher passwordHasher, AuthProperties authProperties, Clock clock) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.preferenceMapper = preferenceMapper;
        this.watchlistMapper = watchlistMapper;
        this.passwordHasher = passwordHasher;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    // ================================================================ 查询

    /** 分页查询：keyword 同时匹配用户名与显示名；role/active 为可选过滤。 */
    public PageResult<UserViews.UserSummary> page(String keyword, String role, Boolean active, PageQuery query) {
        LambdaQueryWrapper<SysUserEntity> wrapper = Wrappers.lambdaQuery();
        if (Strings.isNotBlank(keyword)) {
            String normalizedKeyword = keyword.trim();
            wrapper.and(w -> w.like(SysUserEntity::getUsername, normalizedKeyword)
                    .or().like(SysUserEntity::getDisplayName, normalizedKeyword));
        }
        if (Strings.isNotBlank(role)) {
            wrapper.eq(SysUserEntity::getRole, Enums.requireCode(UserRole.class, role).code());
        }
        if (active != null) {
            wrapper.eq(SysUserEntity::getActive, active);
        }
        wrapper.orderByAsc(SysUserEntity::getId);
        IPage<SysUserEntity> page = userMapper.selectPage(PageResults.toPage(query), wrapper);
        return PageResults.of(page).map(UserService::toSummary);
    }

    /** 用户详情；不存在 → 404。 */
    public UserViews.UserDetail get(long id) {
        return toDetail(requireUser(id));
    }

    /** 用户名反查（登录与唯一性校验用）；不存在返回 empty。 */
    public Optional<SysUserEntity> findByUsername(String username) {
        if (Strings.isBlank(username)) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMapper.selectOne(Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getUsername, username.trim())
                .last("LIMIT 1")));
    }

    /** 账号总数（启动引导判断「是否首次启动」）。 */
    public long countAll() {
        return userMapper.selectCount(Wrappers.lambdaQuery());
    }

    // ================================================================ 写

    /** 创建用户；用户名重复 → 409。 */
    @Transactional
    public UserViews.UserDetail create(UserCreateSpec spec) {
        String username = Asserts.notBlank(spec.username(), "username");
        Asserts.maxLength(username, 64, "username");
        String password = requirePassword(spec.password());
        UserRole role = spec.role() == null ? UserRole.USER : Enums.requireCode(UserRole.class, spec.role());
        if (findByUsername(username).isPresent()) {
            throw ApiException.conflict("用户名已存在: " + username);
        }

        SysUserEntity entity = new SysUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(password));
        entity.setDisplayName(Strings.defaultIfBlank(spec.displayName(), username));
        entity.setRole(role.code());
        entity.setActive(true);
        entity.setRemark(Strings.trimToNull(spec.remark()));
        entity.touch(now());
        userMapper.insert(entity);
        return toDetail(entity);
    }

    /** 修改用户；降级/停用最后一个管理员 → 1004；停用即撤销会话。 */
    @Transactional
    public UserViews.UserDetail update(long id, UserUpdateSpec spec) {
        SysUserEntity user = requireUser(id);
        UserRole currentRole = UserRole.fromCode(user.getRole());
        UserRole targetRole = spec.role() == null ? currentRole : Enums.requireCode(UserRole.class, spec.role());
        boolean targetActive = spec.active() == null ? isActive(user) : spec.active();
        if (currentRole == UserRole.ADMIN && (targetRole != UserRole.ADMIN || !targetActive)) {
            requireAnotherAdmin(user);
        }

        if (spec.displayName() != null) {
            String displayName = Strings.trimToNull(spec.displayName());
            Asserts.maxLength(displayName, 64, "displayName");
            user.setDisplayName(displayName);
        }
        user.setRole(targetRole.code());
        user.setActive(targetActive);
        if (spec.remark() != null) {
            String remark = Strings.trimToNull(spec.remark());
            Asserts.maxLength(remark, 255, "remark");
            user.setRemark(remark);
        }
        user.touch(now());
        userMapper.updateById(user);
        if (!targetActive) {
            revokeSessions(id);
        }
        return toDetail(user);
    }

    /** 删除用户：不能删自己，不能删最后一个管理员；同时清理其会话与个人数据。 */
    @Transactional
    public void delete(long id, long currentUserId) {
        if (id == currentUserId) {
            throw ApiException.of(CommonErrorCode.OPERATION_REJECTED, "不能删除当前登录账号");
        }
        SysUserEntity user = requireUser(id);
        if (UserRole.fromCode(user.getRole()) == UserRole.ADMIN) {
            requireAnotherAdmin(user);
        }
        revokeSessions(id);
        preferenceMapper.delete(Wrappers.<UserPreferenceEntity>lambdaQuery()
                .eq(UserPreferenceEntity::getUserId, id));
        watchlistMapper.delete(Wrappers.<UserWatchlistEntity>lambdaQuery()
                .eq(UserWatchlistEntity::getUserId, id));
        userMapper.deleteById(id);
    }

    /** 管理员重置密码；撤销该用户全部会话。 */
    @Transactional
    public void resetPassword(long id, String newPassword) {
        SysUserEntity user = requireUser(id);
        user.setPasswordHash(passwordHasher.hash(requirePassword(newPassword)));
        user.touch(now());
        userMapper.updateById(user);
        revokeSessions(id);
    }

    /** 用户自助改密：校验原密码 → 落新哈希 → 撤销全部会话（需重新登录）。 */
    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        SysUserEntity user = requireUser(userId);
        String old = Asserts.notBlank(oldPassword, "oldPassword");
        if (!passwordHasher.matches(old, user.getPasswordHash())) {
            throw ApiException.badRequest("原密码错误");
        }
        if (old.equals(newPassword)) {
            throw ApiException.of(CommonErrorCode.OPERATION_REJECTED, "新密码不能与原密码相同");
        }
        user.setPasswordHash(passwordHasher.hash(requirePassword(newPassword)));
        user.touch(now());
        userMapper.updateById(user);
        revokeSessions(userId);
    }

    /** 登录成功后记录最近登录时间（失败登录不写，避免噪音）。 */
    @Transactional
    public void markLogin(long id) {
        SysUserEntity user = requireUser(id);
        user.setLastLoginAt(now());
        user.touch(now());
        userMapper.updateById(user);
    }

    // ================================================================ 校验/转换

    /** 密码策略：非空 + 长度下限（配置）+ 长度上限。返回去空白前的原值（密码可含首尾空格）。 */
    public String requirePassword(String rawPassword) {
        String password = Asserts.notBlank(rawPassword, "password");
        int minLength = authProperties.passwordMinLength();
        Asserts.isTrue(password.length() >= minLength,
                "密码长度至少 " + minLength + " 位（当前 " + password.length() + "）");
        Asserts.isTrue(password.length() <= PASSWORD_MAX_LENGTH,
                "密码长度不能超过 " + PASSWORD_MAX_LENGTH + " 位");
        return password;
    }

    private SysUserEntity requireUser(long id) {
        SysUserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw ApiException.notFound("用户不存在: " + id);
        }
        return user;
    }

    private void requireAnotherAdmin(SysUserEntity excluded) {
        long remaining = userMapper.selectCount(Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getRole, UserRole.ADMIN.code())
                .eq(SysUserEntity::getActive, true)
                .ne(SysUserEntity::getId, excluded.getId()));
        if (remaining <= 0) {
            throw ApiException.of(CommonErrorCode.OPERATION_REJECTED, "至少需要保留一个启用状态的管理员账号");
        }
    }

    private void revokeSessions(long userId) {
        sessionMapper.revokeAllByUserId(userId, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean isActive(SysUserEntity user) {
        return Boolean.TRUE.equals(user.getActive());
    }

    private static UserViews.UserSummary toSummary(SysUserEntity user) {
        UserRole role = UserRole.fromCode(user.getRole());
        return new UserViews.UserSummary(user.getId(), user.getUsername(), user.getDisplayName(),
                role.code(), role.label(), isActive(user), user.getLastLoginAt(), user.getCreatedAt());
    }

    private static UserViews.UserDetail toDetail(SysUserEntity user) {
        UserRole role = UserRole.fromCode(user.getRole());
        return new UserViews.UserDetail(user.getId(), user.getUsername(), user.getDisplayName(),
                role.code(), role.label(), isActive(user), user.getRemark(), user.getLastLoginAt(),
                user.getCreatedAt(), user.getUpdatedAt(),
                role.permissions().stream().map(Permission::code).sorted().toList());
    }
}
