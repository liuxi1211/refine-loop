package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.runtime.validation.validators.FactsUsageValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.JudgmentRuleValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.ReportNumberScanner;
import com.arthur.refineloop.capability.runtime.validation.validators.SchemaValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.TagVocabularyValidator;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.ReferenceFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ValidationChain：顺序、聚合、短路与回喂文本")
class ValidationChainTest {

    private final ValidationChain chain = new ValidationChain(new ValidationValidators(
            new SchemaValidator(),
            new FactsUsageValidator(),
            new TagVocabularyValidator(TagVocabulary.of(List.of("估值处于历史低位"))),
            new JudgmentRuleValidator(new JudgmentRuleSetLoader(), new JudgmentRuleRegistry(List.of())),
            new ReportNumberScanner()));

    @Test
    @DisplayName("全部通过")
    void passesValidOutput() {
        String report = "分位 " + JudgmentBlocks.marker(ReferenceFacts.PE_PERCENTILE, "18.5");

        assertThat(chain.validate(JudgmentBlocks.context(JudgmentBlocks.base(), report)).passed())
                .isTrue();
    }

    @Test
    @DisplayName("顺序固定：schema 错误排在 facts_usage 之前")
    void keepsValidatorOrder() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("confidence", "极高");
        block.put("facts_used", List.of(
                JudgmentBlocks.reference("valuation.percentile_3y.made_up", 1.0, "pct")));

        List<ValidationError> errors = chain.validate(JudgmentBlocks.context(block, "")).errors();

        List<String> validators = errors.stream().map(ValidationError::validator).toList();
        assertThat(validators).contains("schema", "facts_usage");
        assertThat(validators.indexOf("schema")).isLessThan(validators.indexOf("facts_usage"));
    }

    @Test
    @DisplayName("一次跑完五个校验器（问题一次说全）")
    void aggregatesAcrossValidators() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("logic_tags", new ArrayList<>(List.of("越界标签")));
        block.put("facts_used", List.of(
                JudgmentBlocks.reference("valuation.percentile_3y.made_up", 1.0, "pct")));
        String report = "分位 18.5";

        List<ValidationError> errors = chain.validate(JudgmentBlocks.context(block, report)).errors();

        assertThat(errors).extracting(ValidationError::validator)
                .contains("facts_usage", "tag_vocabulary", "report_numbers");
        assertThat(errors).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("输出解析失败短路成一条明确错误（不刷 required）")
    void parseErrorShortCircuits() {
        ValidationResult result = chain.validate(
                JudgmentBlocks.parseErrorContext("未找到 ```json 判断块"));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).validator()).isEqualTo(ValidationChain.PARSE_VALIDATOR);
        assertThat(result.errors().get(0).rule()).isEqualTo("output_format");
    }

    @Test
    @DisplayName("回喂文本编号列出问题；通过时为「无」")
    void feedbackIsNumbered() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("confidence", "极高");

        ValidationResult failed = chain.validate(JudgmentBlocks.context(block, ""));
        assertThat(failed.feedback()).contains("1. [schema]").contains("未通过 L3 校验");

        ValidationResult passed = chain.validate(JudgmentBlocks.context(
                JudgmentBlocks.base(), "分位 " + JudgmentBlocks.marker(ReferenceFacts.PE_PERCENTILE, "18.5")));
        assertThat(passed.feedback()).isEqualTo("无");
    }

    @Test
    @DisplayName("summary 截断首条错误，便于落 data_gaps")
    void summaryIsTruncated() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("confidence", "极高");

        String summary = chain.validate(JudgmentBlocks.context(block, "")).summary();

        assertThat(summary).startsWith("[schema]");
        assertThat(summary.length()).isLessThanOrEqualTo(240);
    }
}
