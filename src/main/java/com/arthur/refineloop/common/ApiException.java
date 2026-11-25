package com.arthur.refineloop.common;

import com.arthur.refineloop.common.error.CommonErrorCode;
import com.arthur.refineloop.common.error.ErrorCode;

import java.util.Objects;

/**
 * 业务异常：可预期的失败（参数不合法、资源不存在、状态冲突、依赖数据未就绪…）。
 *
 * <p>两条使用纪律：
 * <ul>
 *   <li><b>只用来表达「用户/调用方能理解并可能处理」的失败</b>；程序缺陷、NPE、数据库挂了这类
 *       未预期异常不要包成 {@code ApiException}，直接抛出由兜底处理器记 error 日志；</li>
 *   <li>抛出后由 {@code web.GlobalExceptionHandler} 统一转 {@link Result}，日志级别为 <b>warn</b>
 *       （无需堆栈）。不要自己 catch 再返回 {@code Result.fail}——那会绕过统一出口。</li>
 * </ul>
 *
 * <p>编码优先使用 {@link ErrorCode} 枚举；{@link #ApiException(int, String)} 仅为兼容早期代码保留。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode").message(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 兼容早期代码：直接给出裸 code 与文案（内部包装成 {@link ErrorCode}）。 */
    public ApiException(int code, String message) {
        this(new RawErrorCode(code, message), message, null);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.code();
    }

    /* ---------------- 语义化工厂：覆盖绝大多数场景 ---------------- */

    public static ApiException badRequest(String message) {
        return new ApiException(CommonErrorCode.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(CommonErrorCode.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(CommonErrorCode.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(CommonErrorCode.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(CommonErrorCode.CONFLICT, message);
    }

    /** 业务规则拒绝（格式合法但不允许）：1001。 */
    public static ApiException reject(String message) {
        return new ApiException(CommonErrorCode.VALIDATION_FAILED, message);
    }

    /** 依赖数据未就绪：1003。 */
    public static ApiException dataNotReady(String message) {
        return new ApiException(CommonErrorCode.DATA_NOT_READY, message);
    }

    /** 依赖不可用：503。 */
    public static ApiException unavailable(String message) {
        return new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException of(ErrorCode errorCode) {
        return new ApiException(errorCode);
    }

    public static ApiException of(ErrorCode errorCode, String message) {
        return new ApiException(errorCode, message);
    }

    /** 裸 code 的 {@link ErrorCode} 适配（仅供兼容构造器使用）。 */
    private record RawErrorCode(int code, String message) implements ErrorCode {
    }
}
