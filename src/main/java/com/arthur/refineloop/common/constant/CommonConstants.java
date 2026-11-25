package com.arthur.refineloop.common.constant;

/**
 * 全项目通用常量（只放「跨域共用且不会随业务变化」的字面量）。
 *
 * <p>反例：表名、状态码、Tushare 字段名等属于各域事实，放在各自域的枚举/注册表里，
 * 不要往这里堆。分页默认值见 {@code common.page.PageQuery}，缓存名见 {@link CacheNames}。
 */
public final class CommonConstants {

    /**
     * 业务 API 统一前缀：外部 URL 一律 {@code /api/...}（运维 API 在 {@code /api/ops} 下）。
     *
     * <p><b>不要</b>在 Controller 里手写它：前缀由 {@code config.WebMvcConfig#configurePathMatch}
     * 通过 {@code addPathPrefix} 对 {@code web} 包统一挂载，Controller 只声明
     * {@code @RequestMapping("/users")} 这类资源路径。手写会造成 {@code /api/api/users}，
     * 由 {@code ApiPathPrefixTest} 拦截。
     *
     * <p>为什么整站保留 {@code /api} 这一层：前端是 SPA（页面不是文件地址），后端与静态资源
     * 同源部署在 Nginx 后面，反向代理必须在响应生成<b>之前</b>决定由谁处理；
     * 路径前缀是唯一在 SPA 下稳定的处理器信号。若将来把 API 拆到独立 host/port
     * （浏览器直接跨源访问后端），这条约束即可取消。
     */
    public static final String API_PREFIX = "/api";

    /** 系统自动操作（定时任务、初始化数据）的操作人标识。 */
    public static final String SYSTEM_OPERATOR = "system";

    /** 未知/缺省占位符。 */
    public static final String UNKNOWN = "unknown";

    public static final String EMPTY = "";
    public static final String COMMA = ",";
    public static final String SEMICOLON = ";";
    public static final String SLASH = "/";
    public static final String DASH = "-";

    /** 脱敏占位。 */
    public static final String MASK = "******";

    private CommonConstants() {
    }
}
