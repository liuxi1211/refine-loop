package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.error.ErrorCode;

/**
 * LLM 调用域错误码（4101-4199）。
 */
public enum LlmErrorCode implements ErrorCode {

    CALL_FAILED(4101, "LLM 调用失败"),
    TIMEOUT(4102, "LLM 调用超时"),
    RATE_LIMITED(4103, "LLM 调用被限流"),
    NOT_CONFIGURED(4104, "LLM 未配置"),
    PROMPT_NOT_FOUND(4105, "提示词不存在"),
    RESPONSE_EMPTY(4106, "LLM 返回为空"),
    PROMPT_VARIABLE_MISSING(4107, "提示词变量缺失");

    private final int code;
    private final String message;

    LlmErrorCode(int code, String message) {
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
