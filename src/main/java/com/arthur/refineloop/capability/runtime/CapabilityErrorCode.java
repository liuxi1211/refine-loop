package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.common.error.ErrorCode;

/**
 * 能力单元域错误码（4001-4099）。
 *
 * <p>编码规范见 {@code .claude/rules/04-接口契约规范.md}：能力单元段 4001+。
 */
public enum CapabilityErrorCode implements ErrorCode {

    DUPLICATE_UNIT(4001, "能力单元重复注册"),
    INVALID_UNIT(4002, "能力单元定义不合法"),
    FACT_INVALID(4003, "事实数据不合法或缺失"),
    UNIT_NOT_FOUND(4004, "能力单元未注册"),
    VALIDATION_CONFIG_INVALID(4005, "判断校验配置不合法");

    private final int code;
    private final String message;

    CapabilityErrorCode(int code, String message) {
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
