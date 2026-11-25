package com.arthur.refineloop.capability.runtime.llm;

import java.math.BigDecimal;

/**
 * 一次 LLM 调用的结果与用量。
 *
 * @param content         返回文本
 * @param model           实际模型名
 * @param promptTokens    输入 token
 * @param completionTokens 输出 token
 * @param totalTokens     总 token
 * @param cost            费用估算（按 profile 配置单价，元/百万 token 口径由配置决定）
 * @param attempts        实际尝试次数
 * @param durationMs      耗时（毫秒）
 */
public record LlmCallResult(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        BigDecimal cost,
        int attempts,
        long durationMs) {
}
