package com.arthur.refineloop.common.constant;

/**
 * HTTP 头常量：内部约定的自定义头集中在此，避免各处硬编码字符串漂移。
 */
public final class HeaderNames {

    /** 链路追踪号：请求入口生成/透传，写回响应头，并进 MDC 供日志输出。 */
    public static final String TRACE_ID = "X-Trace-Id";

    /** 上游请求号（可选）：前端/网关传入，仅用于对账。 */
    public static final String REQUEST_ID = "X-Request-Id";

    /** 客户端标识（可选）：区分 web / cli / scheduler。 */
    public static final String CLIENT = "X-Client";

    public static final String AUTHORIZATION = "Authorization";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String USER_AGENT = "User-Agent";
    public static final String ACCEPT = "Accept";

    /** 通用 JSON 媒体类型。 */
    public static final String APPLICATION_JSON = "application/json";

    private HeaderNames() {
    }
}
