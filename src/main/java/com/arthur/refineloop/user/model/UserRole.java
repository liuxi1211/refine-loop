package com.arthur.refineloop.user.model;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

import java.util.Set;

/**
 * 用户角色（编码 {@code ADMIN}/{@code USER}）：固定角色的权限集合。
 *
 * <p>为什么不做通用 RBAC（角色表 + 权限表 + 关联表）：本系统只有「管理员」与「普通用户」两类真实
 * 角色，动态角色会引入四张表、管理界面与缓存失效问题，却没有业务需求。固定角色 + 权限码已能
 * 覆盖「普通用户不能管理账号、不能触发全量回填」这条边界；将来需要自定义角色时，把
 * {@link #permissions()} 从代码搬到 {@code sys_role} 表即可，接口声明（{@code @RequirePermission}）不用改。
 *
 * <p>最小权限原则：新用户默认 {@link #USER}；{@link #ADMIN} 才拥有账号管理与数据接入能力。
 */
public enum UserRole implements DictEnum {

    /** 管理员：账号管理 + 数据接入 + 系统开关。 */
    ADMIN("ADMIN", "管理员", "err", Set.of(Permission.values())),
    /** 普通用户：查看行情/作业/统计，维护自己的偏好与自选股。 */
    USER("USER", "普通用户", "run", Set.of(Permission.DATA_READ));

    private final String code;
    private final String label;
    private final String color;
    private final Set<Permission> permissions;

    UserRole(String code, String label, String color, Set<Permission> permissions) {
        this.code = code;
        this.label = label;
        this.color = color;
        this.permissions = Set.copyOf(permissions);
    }

    @Override
    public String dictType() {
        return DictTypes.USER_ROLE;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String color() {
        return color;
    }

    @Override
    public int sort() {
        return this == ADMIN ? 1 : 2;
    }

    /** 该角色拥有的权限（不可变集合）。 */
    public Set<Permission> permissions() {
        return permissions;
    }

    /** 是否拥有指定权限。 */
    public boolean has(Permission permission) {
        return permission != null && permissions.contains(permission);
    }

    /** 按编码解析；未知/空值回退 {@link #USER}（最小权限，绝不因脏数据提权）。 */
    public static UserRole fromCode(String code) {
        return com.arthur.refineloop.common.util.Enums.fromCode(UserRole.class, code).orElse(USER);
    }
}
