package com.arthur.refineloop.web;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.error.CommonErrorCode;
import com.arthur.refineloop.common.log.TraceContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常出口：所有异常在这里统一转成 {@link Result}，并确定日志级别。
 *
 * <p><b>日志级别就是异常分类</b>（与「只有明确异常才 error」的规范一致）：
 * <table border="1">
 *   <tr><th>异常</th><th>含义</th><th>级别</th></tr>
 *   <tr><td>{@link ApiException}</td><td>可预期业务失败（参数、不存在、冲突、未就绪）</td><td>warn</td></tr>
 *   <tr><td>参数校验/反序列化/方法不支持等</td><td>调用方用法错误</td><td>warn</td></tr>
 *   <tr><td>{@link DataAccessException}</td><td>数据库不可用（SQLite 文件锁定/损坏）</td><td>warn（503）</td></tr>
 *   <tr><td>其余 {@link Exception}</td><td>程序缺陷或环境故障</td><td><b>error + 堆栈</b></td></tr>
 * </table>
 *
 * <p>响应文案只给用户可读内容；堆栈只进日志，日志行带 traceId，响应体里的 traceId
 * 让用户可以直接报给我们对账。HTTP 状态统一 200，业务语义由 {@code code} 表达
 * （前端 {@code http.ts} 按此解包）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：可预期，不打印堆栈（避免日志被正常的 400 刷爆）。 */
    @ExceptionHandler(ApiException.class)
    public Result<Void> handleApi(ApiException e) {
        log.warn("业务异常 code={} msg={} traceId={}", e.getCode(), e.getMessage(),
                TraceContext.currentTraceIdOrNone());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 请求体/表单校验失败（@Valid + MethodArgumentNotValidException/BindException）。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        if (detail.isBlank()) {
            detail = e.getBindingResult().getGlobalErrors().stream()
                    .map(error -> error.getDefaultMessage())
                    .collect(Collectors.joining("；"));
        }
        log.warn("参数校验失败 detail={} traceId={}", detail, TraceContext.currentTraceIdOrNone());
        return Result.fail(CommonErrorCode.BAD_REQUEST, "请求参数校验失败：" + detail);
    }

    /** 方法参数上的约束校验（类标 @Validated + @RequestParam/@PathVariable 注解）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 detail={} traceId={}", detail, TraceContext.currentTraceIdOrNone());
        return Result.fail(CommonErrorCode.BAD_REQUEST, "请求参数校验失败：" + detail);
    }

    /**
     * 数据库访问失败：SQLite 库文件被占用/不可写/损坏。
     *
     * <p>刻意返回 503 而不是 500：这是「依赖不可用」而不是程序缺陷，运维 UI 需要给出
     * 「检查 DB_URL 与库文件权限」这类可操作提示。
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.warn("数据库访问失败（SQLite 库文件可能被锁定/不可写）traceId={} err={}",
                TraceContext.currentTraceIdOrNone(), e.getMessage());
        return Result.fail(CommonErrorCode.SERVICE_UNAVAILABLE,
                "数据库不可用（SQLite 库文件打开/写入失败），请检查 DB_URL 与库文件权限");
    }

    /** 参数缺失/类型不符/请求体不是合法 JSON。 */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            HttpMessageNotReadableException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("请求参数错误 traceId={} err={}", TraceContext.currentTraceIdOrNone(), e.getMessage());
        return Result.fail(CommonErrorCode.BAD_REQUEST, "请求参数错误：" + rootMessage(e));
    }

    /** 路由/方法/媒体类型层面的用法错误。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 traceId={} err={}", TraceContext.currentTraceIdOrNone(), e.getMessage());
        return Result.fail(CommonErrorCode.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("请求内容类型不支持 traceId={} err={}", TraceContext.currentTraceIdOrNone(), e.getMessage());
        return Result.fail(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持：" + e.getContentType());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大 traceId={} err={}", TraceContext.currentTraceIdOrNone(), e.getMessage());
        return Result.fail(CommonErrorCode.PAYLOAD_TOO_LARGE, "上传内容过大");
    }

    /** 访问不存在的路径：以前会落到兜底分支变成 500，这里明确 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("路径不存在 traceId={} path={}", TraceContext.currentTraceIdOrNone(), e.getResourcePath());
        return Result.fail(CommonErrorCode.NOT_FOUND, "接口不存在：" + e.getResourcePath());
    }

    /** 异步请求超时（轮询/长任务接口）。 */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Result<Void> handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.warn("异步请求超时 traceId={}", TraceContext.currentTraceIdOrNone());
        return Result.fail(CommonErrorCode.GATEWAY_TIMEOUT, "请求处理超时，请稍后重试");
    }

    /** 兜底：只有走到这里才是真正的「明确异常」，error 级 + 完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        String traceId = TraceContext.currentTraceIdOrNone();
        log.error("未处理异常 traceId={}", traceId, e);
        return Result.fail(CommonErrorCode.INTERNAL_ERROR,
                "内部错误，请稍后重试（traceId=" + traceId + "，可凭此号查日志）");
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    /** 取最内层异常的消息（Spring 包装异常的外层文案往往是泛化的 "JSON parse error"）。 */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
