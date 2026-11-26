package com.arthur.refineloop.capability.runtime.validation.rule;

import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("JudgmentRuleSetLoader：rules.json 装配")
class JudgmentRuleSetLoaderTest {

    private final JudgmentRuleSetLoader loader = new JudgmentRuleSetLoader();

    @Test
    @DisplayName("规则文件与 Schema 同版本同目录：{unit}-v{X}.json → .rules.json")
    void derivesRulesPathFromSchemaId() {
        assertThat(JudgmentRuleSetLoader.rulesResource("interpret_reference-v1.json"))
                .isEqualTo("schema/interpret_reference-v1.rules.json");
        assertThat(JudgmentRuleSetLoader.rulesResource("interpret_reference-v1"))
                .isEqualTo("schema/interpret_reference-v1.rules.json");
    }

    @Test
    @DisplayName("参考实现规则集为空（通用规则由五重链覆盖）")
    void loadsEmptyRuleSet() {
        JudgmentRuleSet ruleSet = loader.load("interpret_reference-v1.json");

        assertThat(ruleSet.isEmpty()).isTrue();
        assertThat(ruleSet.description()).isNotBlank();
    }

    @Test
    @DisplayName("规则文件不存在视为「无特有规则」，不报错")
    void missingFileMeansNoRules() {
        assertThat(loader.load("interpret_reference-v2.json").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("解析 id 与 params")
    void parsesSpecAndParams() {
        JudgmentRuleSet ruleSet = loader.load("test-stubrule-v1.json");

        assertThat(ruleSet.size()).isEqualTo(1);
        JudgmentRuleSpec spec = ruleSet.rules().get(0);
        assertThat(spec.id()).isEqualTo("stub_rule");
        assertThat(spec.params()).containsEntry("threshold", 2);
    }

    @Test
    @DisplayName("非法 JSON → 4005")
    void rejectsBrokenJson() {
        ApiException error = catchThrowableOfType(
                () -> loader.load("test-broken-v1.json"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("合法 JSON");
    }

    @Test
    @DisplayName("规则 id 重复 → 4005（装配歧义）")
    void rejectsDuplicateRuleId() {
        ApiException error = catchThrowableOfType(
                () -> loader.load("test-duplicate-v1.json"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("id 重复");
    }

    @Test
    @DisplayName("schemaId 为空 → 4005")
    void rejectsBlankSchemaId() {
        ApiException error = catchThrowableOfType(() -> loader.load(" "), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
    }
}
