package com.arthur.refineloop.capability.runtime.validation.rule;

import com.arthur.refineloop.capability.spi.FactBundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条规则的执行输入。
 *
 * <p>为什么带 {@code facts}：判定「价位必须引用 calc_key_levels 事实表」（能力详案 6.5-4）
 * 需要拿到同一标的的事实，而不是只看判断块自洽。
 *
 * @param judgmentBlock 判断块
 * @param facts         标的的事实视图
 * @param params        本条规则在 rules.json 中的参数（可为空 Map）
 * @param unitId        当前单元 id
 * @param unitVersion   当前单元版本
 */
public record JudgmentRuleContext(
        Map<String, Object> judgmentBlock,
        FactBundle facts,
        Map<String, Object> params,
        String unitId,
        String unitVersion) {

    public JudgmentRuleContext {
        judgmentBlock = judgmentBlock == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(judgmentBlock));
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
