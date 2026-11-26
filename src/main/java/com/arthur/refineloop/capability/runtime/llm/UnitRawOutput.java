package com.arthur.refineloop.capability.runtime.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 原始输出的结构化拆解：报告正文 + 判断块 + 解析错误。
 *
 * <p>为什么把「解析错误」也作为一等字段：模型漏掉判断块时，问题在输出格式而不在字段内容；
 * 让校验链短路成一条明确错误（而不是一堆 required 缺失），重试才可能一次改对。
 *
 * @param reportMd        报告 Markdown（已去掉判断块围栏）
 * @param judgmentBlock   结构化判断块；解析失败时为空 Map
 * @param judgmentParseError 判断块缺失/非法 JSON 的原因；成功时为 null
 * @param rawText         LLM 原始返回（排查与黄金样本录制用，禁止整段打日志）
 */
public record UnitRawOutput(
        String reportMd,
        Map<String, Object> judgmentBlock,
        String judgmentParseError,
        String rawText) {

    public UnitRawOutput {
        reportMd = reportMd == null ? "" : reportMd;
        judgmentBlock = judgmentBlock == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(judgmentBlock));
        rawText = rawText == null ? "" : rawText;
    }

    /** 解析失败时的输出：报告正文尽量保留，判断块为空。 */
    public static UnitRawOutput unparsed(String rawText, String reportMd, String error) {
        return new UnitRawOutput(reportMd, Map.of(), error, rawText);
    }

    public boolean hasJudgmentBlock() {
        return !judgmentBlock.isEmpty();
    }
}
