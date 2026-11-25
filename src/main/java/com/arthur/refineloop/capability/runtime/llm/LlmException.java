package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.ApiException;

/**
 * LLM 调用异常：统一承载 4101-4107 错误码。
 *
 * <p>对外仍走 {@link ApiException} 的统一出口；{@link #isRetryable()} 供 {@link LlmInvoker} 判定。
 */
public class LlmException extends ApiException {

    public LlmException(LlmErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    public static LlmException notConfigured(String message) {
        return new LlmException(LlmErrorCode.NOT_CONFIGURED, message, null);
    }

    public static LlmException timeout(String message, Throwable cause) {
        return new LlmException(LlmErrorCode.TIMEOUT, message, cause);
    }

    public static LlmException rateLimited(String message, Throwable cause) {
        return new LlmException(LlmErrorCode.RATE_LIMITED, message, cause);
    }

    public static LlmException callFailed(String message, Throwable cause) {
        return new LlmException(LlmErrorCode.CALL_FAILED, message, cause);
    }

    public static LlmException promptNotFound(String message) {
        return new LlmException(LlmErrorCode.PROMPT_NOT_FOUND, message, null);
    }

    public static LlmException responseEmpty(String message) {
        return new LlmException(LlmErrorCode.RESPONSE_EMPTY, message, null);
    }

    public static LlmException promptVariableMissing(String message) {
        return new LlmException(LlmErrorCode.PROMPT_VARIABLE_MISSING, message, null);
    }

    /** 429 可重试；超时按业务决策不重试。 */
    public boolean isRateLimited() {
        return getCode() == LlmErrorCode.RATE_LIMITED.code();
    }

    public boolean isTimeout() {
        return getCode() == LlmErrorCode.TIMEOUT.code();
    }
}
