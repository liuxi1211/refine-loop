package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.common.util.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次校验的聚合结果：错误清单 + 回喂文本。
 *
 * <p>为什么是列表而不是「首个错误即返回」：一次重试的成本是秒级 LLM 调用，
 * 把全部问题一次性告诉模型，比逐个试错便宜得多（也避免「修好 A 又冒 B」的拉锯）。
 *
 * @param errors 结构化错误（空 = 通过）
 */
public record ValidationResult(List<ValidationError> errors) {

    /** 回喂文本里最多列出的错误条数：太多会挤爆上下文，反而降低自纠成功率。 */
    public static final int MAX_FEEDBACK_ERRORS = 20;

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationResult pass() {
        return new ValidationResult(List.of());
    }

    public boolean passed() {
        return errors.isEmpty();
    }

    public int size() {
        return errors.size();
    }

    /** 首条错误摘要（用于日志与降级原因，避免整段错误刷进 data_gaps）。 */
    public String summary() {
        return errors.isEmpty() ? "无" : Strings.truncate(errors.get(0).describe(), 240);
    }

    /**
     * 生成回喂给模型的文本：编号列出问题，超出上限时给出汇总条数。
     *
     * <p>文本直接进 prompt 的 {@code {{validation_feedback}}}，因此必须自解释：
     * 模型看到的不是「校验失败」，而是「哪个字段、错在哪、期望什么」。
     */
    public String feedback() {
        if (errors.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        lines.add("上一次输出未通过 L3 校验，请只修正下列问题后重新输出完整结果：");
        int limit = Math.min(errors.size(), MAX_FEEDBACK_ERRORS);
        for (int i = 0; i < limit; i++) {
            lines.add((i + 1) + ". " + errors.get(i).describe());
        }
        if (errors.size() > limit) {
            lines.add("（另有 " + (errors.size() - limit) + " 条同类问题未逐条列出）");
        }
        return String.join("\n", lines);
    }
}
