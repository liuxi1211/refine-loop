package com.arthur.refineloop.config;

import com.arthur.refineloop.common.constant.CommonConstants;
import com.arthur.refineloop.web.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：统一 API 前缀 + 全局鉴权拦截，两件事都只在这里定义一次。
 *
 * <p>依赖方向：{@code config → web}（装配根允许依赖所有层）。
 *
 * <h2>为什么前缀在这里统一挂载</h2>
 * 业务 URL 必须是 {@code /api/...}，但**这个事实不应该写在每个 Controller 里**：
 * 11 个 Controller 各自拼一遍 {@code API_PREFIX} 只会制造噪音，且漏拼一个就是路由事故。
 * 因此改由 {@link PathMatchConfigurer#addPathPrefix} 对所有控制器的映射统一加前缀，
 * Controller 只声明自己的资源路径（{@code @RequestMapping("/users")} → 实际 {@code /api/users}）。
 *
 * <p>前缀的判定用<b>包名</b>而不是 {@code @RestController} 注解：springdoc 等第三方库的
 * controller 不在 {@code com.arthur.refineloop.web} 下，不能被误加前缀（否则
 * {@code /v3/api-docs} 会变成 {@code /api/v3/api-docs}）。
 * 包名与「所有 {@code @RestController} 必须位于 web 包」这条 ArchUnit 规则互为保证。
 *
 * <h2>为什么拦截器拦 {@code /**} 而不是 {@code /api/**}</h2>
 * 鉴权不应该依赖「开发者记得加对前缀」这种约定——那是静默失败：忘加前缀 ⇒ 接口裸奔且测不出来。
 * 反过来忘登记白名单 ⇒ 接口打不开，立刻有人报错。因此安全边界取 {@code /**}（默认全拦），
 * 白名单是唯一的例外清单，{@code PUBLIC_PATHS} 里每多一行都必须有理由。
 *
 * <p>代价：将来若 Spring 自己要提供静态资源（当前没有，前端由 Nginx/Vite 托管），
 * 那些请求也会被拦成 401。这是「方向正确」的失败——要么登记白名单，要么把静态资源挪走。
 *
 * <p>公开端点白名单（新增公开端点必须在这里登记，否则会被 401 拦截）：
 * <ul>
 *   <li>{@code /api/health}：探活，供容器/负载均衡无凭据探测；</li>
 *   <li>{@code /api/auth/login}：登录本身不能要求登录；</li>
 *   <li>{@code /error}：Servlet 容器的错误转发目标，拦掉会把真实错误掩盖成 401；</li>
 *   <li>Swagger/OpenAPI 文档：只读接口说明，不含业务数据。</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** API 控制器所在包：{@link #configurePathMatch} 用它划定加前缀的范围。 */
    private static final String API_CONTROLLER_PACKAGE = "com.arthur.refineloop.web";

    private static final String[] PUBLIC_PATHS = {
            CommonConstants.API_PREFIX + "/health",
            CommonConstants.API_PREFIX + "/auth/login",
            "/error",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /** 给 web 包下所有控制器统一加 {@code /api} 前缀，Controller 不再手写。 */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(CommonConstants.API_PREFIX,
                HandlerTypePredicate.forBasePackage(API_CONTROLLER_PACKAGE));
    }

    /**
     * 所有路由默认要求登录（fail-closed），只有 {@link #PUBLIC_PATHS} 例外。
     *
     * <p>若将来启用跨源调用，还需要在此放行 {@code OPTIONS} 预检请求，
     * 否则浏览器 preflight（不带令牌）会被直接 401。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }
}
