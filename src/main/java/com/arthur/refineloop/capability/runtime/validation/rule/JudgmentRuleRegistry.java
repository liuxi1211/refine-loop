package com.arthur.refineloop.capability.runtime.validation.rule;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Strings;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则注册表：规则 id → 实现，启动期由 Spring 注入全部 {@link JudgmentRule} Bean 后冻结。
 *
 * <p>为什么不按类型扫描注解：id 必须与 rules.json 字面量对齐，显式注册能让「代码里有、
 * 资源里没有」和「资源里有、代码里没有」两类脱钩都在启动/首次校验时暴露。
 *
 * <p>不变量：id 唯一且非空白；注册后只读（流水线并发执行期间不得增删规则）。
 */
public class JudgmentRuleRegistry {

    private final Map<String, JudgmentRule> rules;

    public JudgmentRuleRegistry(List<JudgmentRule> rules) {
        this.rules = index(rules);
    }

    /** 按 id 查找；不存在返回 null（由调用方判定是配置缺陷）。 */
    public JudgmentRule find(String id) {
        return Strings.isBlank(id) ? null : rules.get(id);
    }

    /** 按 id 查找；不存在抛 4005。 */
    public JudgmentRule require(String id) {
        JudgmentRule rule = find(id);
        if (rule == null) {
            throw ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID,
                    "判断规则未注册：" + id + "（检查 rules.json 与规则实现的版本是否一致）");
        }
        return rule;
    }

    public Collection<String> ids() {
        return rules.keySet();
    }

    public int size() {
        return rules.size();
    }

    private static Map<String, JudgmentRule> index(List<JudgmentRule> rules) {
        Map<String, JudgmentRule> indexed = new LinkedHashMap<>();
        if (rules == null) {
            return Map.of();
        }
        for (JudgmentRule rule : rules) {
            if (rule == null) {
                throw ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID, "存在空的判断规则实例");
            }
            String id = Strings.trimToNull(rule.id());
            if (id == null) {
                throw ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID,
                        "判断规则缺少 id：" + rule.getClass().getName());
            }
            if (indexed.putIfAbsent(id, rule) != null) {
                throw ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID,
                        "判断规则 id 重复：" + id + "（" + rule.getClass().getName() + "）");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
