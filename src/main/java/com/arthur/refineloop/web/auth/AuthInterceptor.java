package com.arthur.refineloop.web.auth;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.user.model.AuthenticatedUser;
import com.arthur.refineloop.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证 + 授权拦截器：所有路由的统一入口（{@code /api/health}、{@code /api/auth/login} 等公开端点除外）。
 *
 * <p>挂载范围是 {@code /**} 而不是 {@code /api/**}：鉴权不能依赖「开发者记得加对前缀」，
 * 那种漏配是静默失败；拦全部则漏配白名单会立刻 401，方向是正确的。白名单见
 * {@code config.WebMvcConfig.PUBLIC_PATHS}。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>解析 {@code Authorization: Bearer <token>}；缺失/无效/过期 → 401（由全局异常出口转 {@code Result.code=401}）；</li>
 *   <li>写入 {@link CurrentUserContext}，供控制器读取当前用户；</li>
 *   <li>若处理器（方法或类）标注 {@link RequirePermission}，校验权限；不足 → 403；</li>
 *   <li>请求结束（含异常）清理 ThreadLocal，避免线程池串号。</li>
 * </ol>
 *
 * <p>依赖方向：{@code web → user.service}（只依赖认证用例），不访问 mapper/entity。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = BearerTokens.extract(request.getHeader(HttpHeaders.AUTHORIZATION));
        AuthenticatedUser user = authService.authenticate(token)
                .orElseThrow(() -> ApiException.unauthorized("未认证或登录已过期，请先登录"));
        CurrentUserContext.set(user);
        try {
            if (handler instanceof HandlerMethod handlerMethod) {
                RequirePermission required = resolvePermission(handlerMethod);
                if (required != null && !user.has(required.value())) {
                    throw ApiException.forbidden("无访问权限：" + required.value().code());
                }
            }
            return true;
        } catch (RuntimeException e) {
            // preHandle 抛异常时 afterCompletion 不会执行，必须立即清理，避免线程池复用串号
            CurrentUserContext.clear();
            throw e;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUserContext.clear();
    }

    /** 方法级优先，类级兜底。 */
    private static RequirePermission resolvePermission(HandlerMethod handlerMethod) {
        RequirePermission methodLevel = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        return handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
    }
}
