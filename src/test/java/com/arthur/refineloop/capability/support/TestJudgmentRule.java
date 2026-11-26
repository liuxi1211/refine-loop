package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRule;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：可编程的判断规则桩（用来验证规则集装配、执行顺序与错误聚合）。
 *
 * <p>不校验任何业务语义：命中条件由构造参数决定，避免测试依赖未来 T15 的真实规则。
 */
public class TestJudgmentRule implements JudgmentRule {

    private final String id;
    private final String failPath;
    private final List<JudgmentRuleContext> invocations = new ArrayList<>();

    public TestJudgmentRule(String id) {
        this(id, null);
    }

    /**
     * @param id       规则 id（与 rules.json 对齐）
     * @param failPath 非空时每次执行都产出该路径的错误；为空则永通过
     */
    public TestJudgmentRule(String id, String failPath) {
        this.id = id;
        this.failPath = failPath;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<ValidationError> check(JudgmentRuleContext context) {
        invocations.add(context);
        if (failPath == null) {
            return List.of();
        }
        return List.of(error(failPath, "stub_rule", "桩规则命中：" + failPath));
    }

    public List<JudgmentRuleContext> invocations() {
        return List.copyOf(invocations);
    }
}
