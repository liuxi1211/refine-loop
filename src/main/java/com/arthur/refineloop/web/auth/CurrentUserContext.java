package com.arthur.refineloop.web.auth;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.user.model.AuthenticatedUser;

/**
 * 请求级登录态（ThreadLocal）。
 *
 * <p>职责：在 {@link AuthInterceptor} 认证成功后写入，控制器读取「当前是谁」，请求结束（含异常）
 * 由拦截器 {@code afterCompletion} 清理。用 ThreadLocal 而不是把用户参数逐层下传，是因为
 * 「当前用户」是横切上下文，服务层的方法签名不应为了它膨胀。
 *
 * <p>不变量：只存放不可变快照（{@link AuthenticatedUser}），不持有实体/连接；线程池复用线程时
 * 必须清理，否则会串号。
 */
public final class CurrentUserContext {

    private static final ThreadLocal<AuthenticatedUser> HOLDER = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    /** 认证成功后写入（仅拦截器调用）。 */
    public static void set(AuthenticatedUser user) {
        HOLDER.set(user);
    }

    /** 取当前用户；未认证（理论上只会在公开端点发生）→ 401。 */
    public static AuthenticatedUser require() {
        AuthenticatedUser user = HOLDER.get();
        if (user == null) {
            throw ApiException.unauthorized("未认证或登录已过期，请先登录");
        }
        return user;
    }

    /** 请求结束清理（拦截器 afterCompletion 调用）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
