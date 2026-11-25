package com.arthur.refineloop.capability.runtime.llm;

/**
 * 一次 LLM 调用的输入规格。
 *
 * @param profile      模型档位（fast / strong）
 * @param unitId       关联单元 id（用量记账维度，可为空）
 * @param runId        流水线运行号；为空时由 {@link LlmInvoker} 回退 MDC
 * @param systemPrompt 系统提示词
 * @param userPrompt   用户提示词
 * @param temperature  覆盖档位默认温度，可为 null
 * @param maxTokens    覆盖档位默认最大 token，可为 null
 */
public record LlmCallSpec(
        String profile,
        String unitId,
        String runId,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens) {

    public LlmCallSpec {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile 不能为空");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt 不能为空");
        }
    }

    public static LlmCallSpec of(String profile, String unitId, String runId,
                                 String systemPrompt, String userPrompt) {
        return new LlmCallSpec(profile, unitId, runId, systemPrompt, userPrompt, null, null);
    }
}
