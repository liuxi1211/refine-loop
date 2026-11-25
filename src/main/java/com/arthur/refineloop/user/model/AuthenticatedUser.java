package com.arthur.refineloop.user.model;

import java.util.Set;

/**
 * 当前登录用户（认证上下文的不可变快照）。
 *
 * <p>只承载「请求期间需要的身份与能力」，不含密码哈希等敏感字段，因此可以安全地放在
 * {@code web} 层的 ThreadLocal 里，也可以作为 service 入参。
 */
public record AuthenticatedUser(
        long id,
        String username,
        String displayName,
        UserRole role,
        Set<Permission> permissions) {

    public AuthenticatedUser {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permission != null && permissions.contains(permission);
    }

    /** 按权限码判断（供注解拦截器使用，避免在 web 层解析枚举）。 */
    public boolean has(String permissionCode) {
        return Permission.fromCode(permissionCode).filter(permissions::contains).isPresent();
    }

    /** 面向接口的角色展示文案。 */
    public String roleLabel() {
        return role == null ? null : role.label();
    }
}
