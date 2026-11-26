package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.common.util.Strings;

/**
 * 一条结构化的校验错误：哪个字段、违反什么规则、期望是什么。
 *
 * <p>为什么不用字符串：错误要回喂给模型自纠，也要给黄金样本回放做统计；
 * 「字段路径 + 规则名」是唯一能兼顾两种用途的形态（笼统的「校验失败」重试成功率明显更低）。
 *
 * @param validator 产出该错误的校验器/规则 id（如 {@code facts_usage}、{@code schema}）
 * @param path      字段路径（JSON-Path 风格，如 {@code $.facts_used[0].value}）
 * @param rule      违反的规则名（如 {@code value_mismatch}、{@code enum}）
 * @param message   面向模型/人的说明（中文，含期望值）
 */
public record ValidationError(String validator, String path, String rule, String message) {

    public ValidationError {
        validator = Strings.defaultIfBlank(validator, "unknown");
        path = Strings.defaultIfBlank(path, "$");
        rule = Strings.defaultIfBlank(rule, "unspecified");
        message = Strings.nullToEmpty(message);
    }

    /** 单行描述，供回喂文本与日志使用。 */
    public String describe() {
        return "[" + validator + "] " + path + "：" + message + "（rule=" + rule + "）";
    }
}
