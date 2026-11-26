package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRule;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleContext;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSet;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSpec;
import com.arthur.refineloop.common.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * ④ 单元特有规则：按 {@code *.rules.json} 装配的规则集逐条执行。
 *
 * <p>规则集为空的单元直接通过（M1 只有主判断单元有特有规则，见能力详案 6.5）。
 *
 * <p>不变量：
 * <ul>
 *   <li>规则集引用了未注册的规则 id → 4005 fail-fast（代码与资源版本脱钩，不能静默放过判断）；</li>
 *   <li>规则返回的错误按规则集顺序聚合，便于黄金样本稳定比对。</li>
 * </ul>
 */
public class JudgmentRuleValidator implements JudgmentValidator {

    /** 校验器名。 */
    public static final String NAME = "judgment_rule";

    private final JudgmentRuleSetLoader ruleSetLoader;
    private final JudgmentRuleRegistry registry;

    public JudgmentRuleValidator(JudgmentRuleSetLoader ruleSetLoader, JudgmentRuleRegistry registry) {
        this.ruleSetLoader = ruleSetLoader;
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ValidationError> validate(ValidationContext context) {
        JudgmentRuleSet ruleSet = ruleSetLoader.load(context.schemaId());
        if (ruleSet.isEmpty()) {
            return List.of();
        }
        List<ValidationError> errors = new ArrayList<>();
        for (JudgmentRuleSpec spec : ruleSet.rules()) {
            JudgmentRule rule = requireRegistered(spec, context);
            List<ValidationError> found = rule.check(new JudgmentRuleContext(
                    context.judgmentBlock(), context.facts(), spec.params(),
                    context.unitId(), context.unitVersion()));
            if (found != null && !found.isEmpty()) {
                errors.addAll(found);
            }
        }
        return errors;
    }

    private JudgmentRule requireRegistered(JudgmentRuleSpec spec, ValidationContext context) {
        JudgmentRule rule = registry.find(spec.id());
        if (rule == null) {
            throw ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID,
                    "规则集引用了未注册的规则：" + spec.id() + "（schemaId=" + context.schemaId()
                            + "；请检查代码与 rules.json 的版本一致性）");
        }
        return rule;
    }
}
