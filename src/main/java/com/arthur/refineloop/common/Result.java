package com.arthur.refineloop.common;

import com.arthur.refineloop.common.error.CommonErrorCode;
import com.arthur.refineloop.common.error.ErrorCode;

import java.util.function.Function;

/**
 * 统一响应包装：{@code {code, msg, data}}（前后端唯一契约，前端 {@code http.ts} 按此解包）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code=0} 表示成功，其余取值见 {@link CommonErrorCode}（与 HTTP 语义对齐 + 业务码段）；</li>
 *   <li>业务失败同样以 HTTP 200 返回，由 {@code code} 表达语义——前端只在 {@code code != 0} 时进入错误分支；</li>
 *   <li>HTTP 非 200 只表示「接口不可达/网关错误」，前端按网络错误处理。</li>
 * </ul>
 *
 * <p>{@code msg} 只放用户可读文案；排查用的异常栈与内部信息只进日志（带 traceId），不进响应。
 */
public record Result<T>(int code, String msg, T data) {

    public static final int CODE_OK = CommonErrorCode.OK.code();
    public static final int CODE_BAD_REQUEST = CommonErrorCode.BAD_REQUEST.code();
    public static final int CODE_NOT_FOUND = CommonErrorCode.NOT_FOUND.code();
    public static final int CODE_CONFLICT = CommonErrorCode.CONFLICT.code();
    public static final int CODE_ERROR = CommonErrorCode.INTERNAL_ERROR.code();
    public static final int CODE_DB_UNAVAILABLE = CommonErrorCode.SERVICE_UNAVAILABLE.code();

    /* ---------------- 成功 ---------------- */

    public static <T> Result<T> ok(T data) {
        return new Result<>(CODE_OK, CommonErrorCode.OK.message(), data);
    }

    /** 成功但需要自定义提示（如「已受理，异步执行中」）。 */
    public static <T> Result<T> ok(T data, String msg) {
        return new Result<>(CODE_OK, msg, data);
    }

    public static Result<Void> ok() {
        return new Result<>(CODE_OK, CommonErrorCode.OK.message(), null);
    }

    /* ---------------- 失败 ---------------- */

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null);
    }

    /** 覆盖枚举里的通用提示，补上上下文（表名、参数值等）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.code(), msg, null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    /* ---------------- 组合 ---------------- */

    /** 成功时转换数据；失败时原样传递错误码与文案。 */
    public <R> Result<R> map(Function<? super T, ? extends R> mapper) {
        return code == CODE_OK
                ? new Result<>(code, msg, mapper.apply(data))
                : new Result<>(code, msg, null);
    }
}
