package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.TestJudgmentRule;
import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JudgmentRuleValidator：规则集装配与执行")
class JudgmentRuleValidatorTest {

    private final JudgmentRuleSetLoader loader = new JudgmentRuleSetLoader();

    @Test
    @DisplayName("规则集为空直接通过，且不实例化任何规则")
    void emptyRuleSetPasses() {
        TestJudgmentRule rule = new TestJudgmentRule("stub_rule");
        JudgmentRuleValidator validator = new JudgmentRuleValidator(loader,
                new JudgmentRuleRegistry(List.of(rule)));

        assertThat(validator.validate(JudgmentBlocks.context(JudgmentBlocks.base(), ""))).isEmpty();
        assertThat(rule.invocations()).isEmpty();
    }

    @Test
    @DisplayName("按 rules.json 执行规则并透传 params")
    void runsConfiguredRuleWithParams() {
        TestJudgmentRule rule = new TestJudgmentRule("stub_rule");
        JudgmentRuleValidator validator = new JudgmentRuleValidator(loader,
                new JudgmentRuleRegistry(List.of(rule)));

        List<ValidationError> errors = validator.validate(
                JudgmentBlocks.context(JudgmentBlocks.base(), "", "test-stubrule-v1.json"));

        assertThat(errors).isEmpty();
        assertThat(rule.invocations()).hasSize(1);
        assertThat(rule.invocations().get(0).params()).containsEntry("threshold", 2);
        assertThat(rule.invocations().get(0).unitId()).isEqualTo(JudgmentBlocks.UNIT_ID);
    }

    @Test
    @DisplayName("规则失败时错误透传（校验器名 = 规则 id）")
    void propagatesRuleErrors() {
        TestJudgmentRule rule = new TestJudgmentRule("stub_rule", "$.key_levels.stop_loss");
        JudgmentRuleValidator validator = new JudgmentRuleValidator(loader,
                new JudgmentRuleRegistry(List.of(rule)));

        List<ValidationError> errors = validator.validate(
                JudgmentBlocks.context(JudgmentBlocks.base(), "", "test-stubrule-v1.json"));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).validator()).isEqualTo("stub_rule");
        assertThat(errors.get(0).path()).isEqualTo("$.key_levels.stop_loss");
    }

    @Test
    @DisplayName("规则集引用未注册 id → 4005（代码与资源版本脱钩）")
    void unregisteredRuleFailsFast() {
        JudgmentRuleValidator validator = new JudgmentRuleValidator(loader,
                new JudgmentRuleRegistry(List.of(new TestJudgmentRule("stub_rule"))));

        ApiException error = catchThrowableOfType(() -> validator.validate(
                JudgmentBlocks.context(JudgmentBlocks.base(), "", "test-unregistered-v1.json")),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("ghost_rule");
    }

    @Test
    @DisplayName("注册表拒绝重复 id 与空 id")
    void registryRejectsInvalidRules() {
        assertThatThrownBy(() -> new JudgmentRuleRegistry(List.of(
                new TestJudgmentRule("dup"), new TestJudgmentRule("dup"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("id 重复");
        assertThatThrownBy(() -> new JudgmentRuleRegistry(List.of(new TestJudgmentRule(" "))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少 id");
    }
}
