package com.arthur.refineloop.capability.runtime.validation.rule;

import java.util.List;

/**
 * 一个判断单元版本的规则集（{@code schema/{unit_id}-v{X}.rules.json} 的解析结果）。
 *
 * <p>不变量：{@code rules} 顺序即执行顺序，错误按此顺序回喂（稳定顺序便于黄金样本比对）。
 *
 * @param description 规则集说明（人读，可与 SPEC 对齐）
 * @param rules       有序规则项（可为空 = 该单元暂无特有规则）
 */
public record JudgmentRuleSet(String description, List<JudgmentRuleSpec> rules) {

    public JudgmentRuleSet {
        description = description == null ? "" : description;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static JudgmentRuleSet empty() {
        return new JudgmentRuleSet("", List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }
}
