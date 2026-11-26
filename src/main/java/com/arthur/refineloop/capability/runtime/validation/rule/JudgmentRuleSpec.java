package com.arthur.refineloop.capability.runtime.validation.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code rules.json} 里的一条规则装配项：规则 id + 该单元使用它时的参数。
 *
 * <p>不变量：{@code id} 必须非空白；{@code params} 只读（装配期解析后不再变动）。
 *
 * @param id     规则 id（必须已在 {@link JudgmentRuleRegistry} 注册）
 * @param params 规则参数（可为空 Map）
 */
public record JudgmentRuleSpec(String id, Map<String, Object> params) {

    public JudgmentRuleSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("规则 id 不能为空");
        }
        id = id.trim();
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
