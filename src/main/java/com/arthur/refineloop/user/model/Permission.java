package com.arthur.refineloop.user.model;

import java.util.Optional;

/**
 * 权限码：接口能力的稳定编码（{@code 域:动作}），与角色解耦。
 *
 * <p>为什么用权限码而不是直接判角色：接口声明的是「需要什么能力」，角色只是能力的集合；
 * 以后新增「只读账号」或调整某个角色的能力时，只改 {@link UserRole} 的映射，所有接口声明不变。
 *
 * <p>编码只增不改：新增能力追加常量，不复用已有编码。
 */
public enum Permission {

    /** 查看用户列表与详情。 */
    USER_READ("user:read"),
    /** 新建/修改/删除用户、重置密码。 */
    USER_WRITE("user:write"),
    /** 查看行情、数据表、作业与统计。 */
    DATA_READ("data:read"),
    /** 触发数据接入（每日增量/回填/单表作业）。 */
    DATA_INGEST("data:ingest"),
    /** 执行数据质量门。 */
    QUALITY_RUN("quality:run"),
    /** 修改系统级开关（如每日增量总开关）。 */
    SYSTEM_CONFIG("system:config");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 精确匹配编码（大小写敏感，不做猜测）；未命中返回 empty。 */
    public static Optional<Permission> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim();
        for (Permission permission : values()) {
            if (permission.code.equals(normalized)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
