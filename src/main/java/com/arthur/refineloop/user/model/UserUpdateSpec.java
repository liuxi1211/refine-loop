package com.arthur.refineloop.user.model;

/**
 * 修改用户的服务层入参：全部字段可空，{@code null} 表示「不修改」。
 *
 * <p>没有 {@code username}：用户名是登录标识，改名需要单独的动作（避免会话与审计口径混乱）。
 */
public record UserUpdateSpec(
        String displayName,
        String role,
        Boolean active,
        String remark) {
}
