package com.arthur.refineloop.capability.runtime.llm;

/**
 * LLM 协议类型：OpenAI 兼容协议与 Anthropic Messages 协议。
 *
 * <p>“新版/旧版”不新增协议，通过 provider 的 {@code completions-path} /
 * {@code anthropic-version} 覆盖。
 */
public enum LlmProtocol {
    OPENAI,
    ANTHROPIC
}
