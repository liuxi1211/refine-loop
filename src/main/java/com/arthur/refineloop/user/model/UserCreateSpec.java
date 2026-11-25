package com.arthur.refineloop.user.model;

/**
 * 新建用户的服务层入参（替代 5 个位置参数；{@code web} 层负责把请求体转换成本对象）。
 *
 * <p>可空字段：{@code displayName}（默认取 username）、{@code role}（默认 USER）、{@code remark}。
 * {@code password} 必填，长度由服务层按配置校验。
 */
public record UserCreateSpec(
        String username,
        String password,
        String displayName,
        String role,
        String remark) {
}
