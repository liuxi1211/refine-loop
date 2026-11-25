package com.arthur.refineloop.common.error;

/**
 * 通用错误码：沟通层（4xx/5xx 语义）+ 跨域通用业务码（1xxx）。
 *
 * <p>新增业务码请遵循两条纪律：
 * <ol>
 *   <li>先看这里能不能复用——「参数错」永远是 400，「查不到」永远是 404，
 *       不要为每个接口造一个新码；</li>
 *   <li>真正需要前端分支处理（文案/引导不同）的业务失败才新增编码，并落到对应域的枚举里
 *       （如 {@code tushare.ingest.IngestErrorCode}），不要往本枚举里堆业务。 </li>
 * </ol>
 */
public enum CommonErrorCode implements ErrorCode {

    /* ---------------- 成功 ---------------- */
    /** 成功。 */
    OK(0, "ok"),

    /* ---------------- 沟通层：与 HTTP 语义对齐 ---------------- */
    /** 参数缺失/格式错误/校验失败。 */
    BAD_REQUEST(400, "请求参数错误"),
    /** 未认证或凭证过期。 */
    UNAUTHORIZED(401, "未认证或登录已过期"),
    /** 已认证但无权访问。 */
    FORBIDDEN(403, "无访问权限"),
    /** 资源不存在。 */
    NOT_FOUND(404, "资源不存在"),
    /** 请求方法不支持。 */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    /** 状态冲突（并发写、重复触发、幂等拒绝等）。 */
    CONFLICT(409, "操作冲突，请刷新后重试"),
    /** 请求体过大。 */
    PAYLOAD_TOO_LARGE(413, "请求体过大"),
    /** 内容类型不支持。 */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的请求内容类型"),
    /** 触发限流。 */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),
    /** 未预期的服务端错误（只有这一档才允许 error 级日志）。 */
    INTERNAL_ERROR(500, "内部错误"),
    /** 功能尚未实现。 */
    NOT_IMPLEMENTED(501, "功能未实现"),
    /** 上游返回了错误。 */
    BAD_GATEWAY(502, "上游服务异常"),
    /** 依赖不可用（数据库、文件、外部服务）。 */
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),
    /** 上游超时。 */
    GATEWAY_TIMEOUT(504, "上游服务响应超时"),

    /* ---------------- 通用业务码 1xxx ---------------- */
    /** 业务规则校验失败：参数在格式上合法，但业务上不允许。 */
    VALIDATION_FAILED(1001, "业务校验失败"),
    /** 唯一键冲突/重复提交。 */
    DUPLICATE_KEY(1002, "数据已存在"),
    /** 依赖的数据尚未就绪（回填未完成、交易日历缺失等）。 */
    DATA_NOT_READY(1003, "依赖数据尚未就绪"),
    /** 当前状态下不允许该操作（状态机拒绝，区别于并发冲突）。 */
    OPERATION_REJECTED(1004, "当前状态下不允许该操作"),
    /** 不支持的操作/取值。 */
    UNSUPPORTED_OPERATION(1005, "不支持的操作");

    private final int code;
    private final String message;

    CommonErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
