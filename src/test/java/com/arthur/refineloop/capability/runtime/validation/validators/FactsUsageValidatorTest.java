package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.ReferenceFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FactsUsageValidator：指标名 + 数值 + 单位三重比对")
class FactsUsageValidatorTest {

    private final FactsUsageValidator validator = new FactsUsageValidator();

    @Test
    @DisplayName("合法引用通过")
    void acceptsConsistentUsage() {
        assertThat(validator.validate(JudgmentBlocks.context(JudgmentBlocks.base(), ""))).isEmpty();
    }

    @Test
    @DisplayName("18.5 vs 18.499999 通过（相对误差远小于 0.1%）")
    void acceptsTinyNumericDrift() {
        Map<String, Object> block = blockWithPercentile(18.499999);

        assertThat(validator.validate(JudgmentBlocks.context(block, ""))).isEmpty();
    }

    @Test
    @DisplayName("18.5 vs 18.6 失败（相对误差 0.54% > 0.1%）")
    void rejectsTamperedValue() {
        Map<String, Object> block = blockWithPercentile(18.6);

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("value_mismatch");
        assertThat(errors.get(0).path()).isEqualTo("$.facts_used[0].value");
        assertThat(errors.get(0).message()).contains("18.5").contains("18.6");
    }

    @Test
    @DisplayName("单位不符失败（pct vs 倍）")
    void rejectsUnitMismatch() {
        Map<String, Object> block = JudgmentBlocks.base();
        List<Map<String, Object>> used = new ArrayList<>();
        used.add(JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, 18.5, "倍"));
        block.put("facts_used", used);

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("unit_mismatch");
        assertThat(errors.get(0).message()).contains("pct");
    }

    @Test
    @DisplayName("指标不存在 = 编造指标")
    void rejectsFabricatedMetric() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(
                JudgmentBlocks.reference("valuation.percentile_3y.made_up", 12.3, "pct")));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("fabricated_metric");
        assertThat(errors.get(0).message()).contains("不存在指标");
    }

    @Test
    @DisplayName("事实未声明 unit → missing_fact_unit（无法核对口径）")
    void rejectsFactWithoutDeclaredUnit() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metric_without_unit", Map.of("value", 18.5));
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(
                JudgmentBlocks.reference("metric_without_unit", 18.5, "pct")));

        List<ValidationError> errors = validator.validate(
                JudgmentBlocks.context(block, payload, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).extracting(ValidationError::rule).contains("missing_fact_unit");
    }

    @Test
    @DisplayName("value 非数值 → not_a_number")
    void rejectsNonNumericValue() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(
                JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, "十八点五", "pct")));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("not_a_number");
    }

    @Test
    @DisplayName("同一指标重复引用 → duplicate_metric")
    void rejectsDuplicateMetric() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(
                JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, 18.5, "pct"),
                JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, 18.5, "pct")));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).extracting(ValidationError::rule).contains("duplicate_metric");
    }

    @Test
    @DisplayName("facts_used 为空 → empty（不允许零事实的判断）")
    void rejectsEmptyFactsUsed() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of());

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("empty");
    }

    @Test
    @DisplayName("facts_used 不是数组 → type")
    void rejectsNonArrayFactsUsed() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", "pe_ttm=18.5");

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("type");
    }

    @Test
    @DisplayName("事实值为 0 时改用绝对相等：声明 0.0001 判失败")
    void zeroFactRequiresExactZero() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zero_metric", ReferenceFacts.metric(0, "pct"));
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(JudgmentBlocks.reference("zero_metric", 0.0001, "pct")));

        List<ValidationError> errors = validator.validate(
                JudgmentBlocks.context(block, payload, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("value_mismatch");
    }

    @Test
    @DisplayName("数值字符串可被接受（18.50 与 18.5 数值等价）")
    void acceptsNumericStringWithSameScale() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(JudgmentBlocks.reference("zero_metric", "0", "pct")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zero_metric", ReferenceFacts.metric(0, "pct"));

        assertThat(validator.validate(
                JudgmentBlocks.context(block, payload, "", JudgmentBlocks.SCHEMA_ID))).isEmpty();
    }

    @Test
    @DisplayName("按书写精度四舍五入：18.5432→18.5 通过，→18.9 失败")
    void acceptsLastDigitRoundingButNotTampering() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ReferenceFacts.PE_PERCENTILE, ReferenceFacts.metric(18.5432, "pct"));
        Map<String, Object> rounded = JudgmentBlocks.base();
        rounded.put("facts_used", List.of(
                JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, 18.5, "pct")));
        Map<String, Object> tampered = JudgmentBlocks.base();
        tampered.put("facts_used", List.of(
                JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, 18.9, "pct")));

        assertThat(validator.validate(
                JudgmentBlocks.context(rounded, payload, "", JudgmentBlocks.SCHEMA_ID))).isEmpty();
        assertThat(validator.validate(
                JudgmentBlocks.context(tampered, payload, "", JudgmentBlocks.SCHEMA_ID)))
                .extracting(ValidationError::rule).contains("value_mismatch");
    }

    private Map<String, Object> blockWithPercentile(double declared) {
        Map<String, Object> block = JudgmentBlocks.base();
        List<Map<String, Object>> used = new ArrayList<>();
        used.add(JudgmentBlocks.reference(ReferenceFacts.PE_PERCENTILE, declared, "pct"));
        used.add(JudgmentBlocks.reference(ReferenceFacts.PB_PERCENTILE, 22.1, "pct"));
        block.put("facts_used", used);
        return block;
    }
}
