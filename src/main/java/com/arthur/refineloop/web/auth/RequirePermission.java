package com.arthur.refineloop.web.auth;

import com.arthur.refineloop.user.model.Permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限声明：标注「调用本端点需要哪个能力」。
 *
 * <p>用法与语义：
 * <ul>
 *   <li>标在方法上（优先）或控制器类上（类级默认，方法级覆盖）；</li>
 *   <li>由 {@link AuthInterceptor} 在认证之后判定：未认证 → 401；已认证但无权限 → 403；</li>
 *   <li>不标注 = 只要求登录（任何已认证用户可访问，如「我的偏好/自选股」）。</li>
 * </ul>
 *
 * <p>为什么用 {@link Permission} 枚举而不是字符串：拼错编译不过；新增能力时 IDE 能列出全部取值。
 * 权限码与角色映射见 {@code user.model.UserRole}。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限。 */
    Permission value();
}
