package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.validation.ClasspathTagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.runtime.validation.ValidationValidators;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.runtime.validation.validators.FactsUsageValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.JudgmentRuleValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.ReportNumberScanner;
import com.arthur.refineloop.capability.runtime.validation.validators.SchemaValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.TagVocabularyValidator;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.ReferenceFacts;
import com.arthur.refineloop.capability.support.ReferenceInterpretUnit;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AbstractInterpretUnit：生命周期、带错重试、降级")
class AbstractInterpretUnitTest {

    private LlmInvoker invoker;
    private PromptTemplateLoader promptLoader;
    private SchemaValidator schemaValidator;
    private TradingDayCalendar calendar;
    private ValidationChain chain;

    @BeforeEach
    void setUp() {
        invoker = mock(LlmInvoker.class);
        promptLoader = mock(PromptTemplateLoader.class);
        when(promptLoader.load(anyString(), anyString())).thenReturn("TEMPLATE");
        when(promptLoader.render(anyString(), anyMap()))
                .thenAnswer(call -> "RENDERED:" + call.getArgument(1));

        schemaValidator = mock(SchemaValidator.class);
        when(schemaValidator.validate(any())).thenReturn(List.of());
        chain = new ValidationChain(new ValidationValidators(
                schemaValidator,
                passThrough(FactsUsageValidator.class),
                passThrough(TagVocabularyValidator.class),
                passThrough(JudgmentRuleValidator.class),
                passThrough(ReportNumberScanner.class)));
        // 5 个交易日后的日期刻意与自然日兜底不同，便于区分两条路径
        calendar = (tradeDate, tradingDays) -> "20261205";
    }

    @Test
    @DisplayName("成功路径：一次调用通过，返回非降级结果")
    void succeedsOnFirstAttempt() {
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        InterpretResult result = newUnit().interpret(ReferenceFacts.bundle());

        assertThat(result.degraded()).isFalse();
        assertThat(result.reportMd()).contains("估值分位");
        assertThat(result.judgmentBlock()).containsEntry("unit_id", "interpret_reference");
        verify(invoker, times(1)).invoke(any());
    }

    @Test
    @DisplayName("校验失败 → 错误回喂 → 第二次通过")
    void retriesOnceThenSucceeds() {
        when(schemaValidator.validate(any())).thenReturn(
                List.of(new ValidationError("schema", "$.confidence", "enum", "枚举越界")),
                List.of());
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        InterpretResult result = newUnit().interpret(ReferenceFacts.bundle());

        assertThat(result.degraded()).isFalse();
        verify(invoker, times(2)).invoke(any());
        List<LlmCallSpec> specs = capturedSpecs(2);
        assertThat(specs.get(0).systemPrompt()).contains("validation_feedback=无");
        assertThat(specs.get(1).systemPrompt()).contains("枚举越界");
    }

    @Test
    @DisplayName("重试耗尽（1+2 次）→ 机械降级：neutral/低/degraded/5 交易日/direction_fail")
    void exhaustsRetriesThenDegrades() {
        when(schemaValidator.validate(any())).thenReturn(
                List.of(new ValidationError("schema", "$.confidence", "enum", "枚举越界")));
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        InterpretResult result = newUnit().interpret(ReferenceFacts.bundle());

        verify(invoker, times(AbstractInterpretUnit.MAX_RETRY + 1)).invoke(any());
        assertThat(result.degraded()).isTrue();
        assertThat(result.reportMd()).isEmpty();
        assertThat(result.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("重试耗尽"));
        Map<String, Object> block = result.judgmentBlock();
        assertThat(block).containsEntry("degraded", true).containsEntry("confidence", "低");
        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) block.get("direction");
        assertThat(direction).containsEntry("type", "neutral")
                .containsEntry("horizon_days", 5)
                .containsEntry("expire_at", "2026-12-05");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) block.get("verification_rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).containsEntry("rule_type", "direction_fail");
    }

    @Test
    @DisplayName("LLM 调用失败立即降级（不在模板层重复重试）")
    void degradesWhenLlmFails() {
        when(invoker.invoke(any())).thenThrow(ApiException.of(LlmErrorCode.CALL_FAILED, "上游 503"));

        InterpretResult result = newUnit().interpret(ReferenceFacts.bundle());

        verify(invoker, times(1)).invoke(any());
        assertThat(result.degraded()).isTrue();
        assertThat(result.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("LLM 调用失败"));
    }

    @Test
    @DisplayName("交易日历不可用时有效期按自然日近似，并在 data_gaps 留痕")
    void fallsBackToNaturalDaysWhenCalendarUnavailable() {
        calendar = (tradeDate, tradingDays) -> {
            throw ApiException.dataNotReady("交易日历不足");
        };
        when(schemaValidator.validate(any())).thenReturn(
                List.of(new ValidationError("schema", "$.confidence", "enum", "枚举越界")));
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        InterpretResult result = newUnit().interpret(ReferenceFacts.bundle());

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) result.judgmentBlock().get("direction");
        assertThat(direction).containsEntry("expire_at", "2026-12-03");
        assertThat(result.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("自然日近似"));
    }

    @Test
    @DisplayName("走真实校验链与真实 prompt：合法输出一次通过")
    void walksThroughRealValidationChain() {
        ValidationChain realChain = new ValidationChain(new ValidationValidators(
                new SchemaValidator(),
                new FactsUsageValidator(),
                new TagVocabularyValidator(new ClasspathTagVocabulary()),
                new JudgmentRuleValidator(new JudgmentRuleSetLoader(),
                        new JudgmentRuleRegistry(List.of())),
                new ReportNumberScanner()));
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        InterpretResult result = new ReferenceInterpretUnit(invoker, new PromptTemplateLoader(),
                realChain, calendar).interpret(ReferenceFacts.bundle());

        assertThat(result.degraded()).isFalse();
        LlmCallSpec spec = capturedSpecs(1).get(0);
        assertThat(spec.systemPrompt()).contains("interpret_reference").contains("18.5");
        assertThat(spec.profile()).isEqualTo("fast");
        assertThat(spec.unitId()).isEqualTo("interpret_reference");
        assertThat(spec.runId()).isEqualTo("run-t8");
    }

    @Test
    @DisplayName("promptId 与 unitId 不一致 → 4002（不发起任何调用）")
    void rejectsPromptIdMismatch() {
        ApiException error = catchThrowableOfType(
                () -> new WrongPromptUnit(invoker, promptLoader, chain, calendar)
                        .interpret(ReferenceFacts.bundle()),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
        assertThat(error.getMessage()).contains("promptId");
        verify(invoker, never()).invoke(any());
    }

    @Test
    @DisplayName("buildFactsPayload 为空 → 4002（禁止空事实调用）")
    void rejectsEmptyFactsPayload() {
        ApiException error = catchThrowableOfType(
                () -> new NoPayloadUnit(invoker, promptLoader, chain, calendar)
                        .interpret(ReferenceFacts.bundle()),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
        verify(invoker, never()).invoke(any());
    }

    @Test
    @DisplayName("mapOutput 返回空 → 4002（单元实现缺陷，不静默降级）")
    void rejectsNullMappedOutput() {
        when(invoker.invoke(any())).thenReturn(callResult(validOutput()));

        ApiException error = catchThrowableOfType(
                () -> new NullOutputUnit(invoker, promptLoader, chain, calendar)
                        .interpret(ReferenceFacts.bundle()),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
        assertThat(error.getMessage()).contains("mapOutput");
    }

    @Test
    @DisplayName("事实视图为空 → 4002")
    void rejectsNullFacts() {
        ApiException error = catchThrowableOfType(
                () -> newUnit().interpret(null), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
    }

    // ================================================================ 夹具

    private ReferenceInterpretUnit newUnit() {
        return new ReferenceInterpretUnit(invoker, promptLoader, chain, calendar);
    }

    private List<LlmCallSpec> capturedSpecs(int expectedCalls) {
        ArgumentCaptor<LlmCallSpec> captor = ArgumentCaptor.forClass(LlmCallSpec.class);
        verify(invoker, times(expectedCalls)).invoke(captor.capture());
        return captor.getAllValues();
    }

    private static LlmCallResult callResult(String content) {
        return new LlmCallResult(content, "test-model", 10, 20, 30,
                new BigDecimal("0.00005"), 1, 12L);
    }

    /** 合法输出：报告正文全部带标记 + 合法判断块。 */
    private static String validOutput() {
        String report = "## 结论\n估值分位 " + JudgmentBlocks.marker(ReferenceFacts.PE_PERCENTILE, "18.5")
                + "，处于历史低位。";
        return report + "\n```json\n" + JsonUtils.toJson(JudgmentBlocks.base()) + "\n```\n";
    }

    private static <T extends JudgmentValidator> T passThrough(Class<T> type) {
        T validator = mock(type);
        when(validator.validate(any())).thenReturn(List.of());
        return validator;
    }

    @CapabilityUnit(id = "interpret_wrong_prompt", version = "v1.0", type = UnitType.INTERPRET)
    static class WrongPromptUnit extends AbstractInterpretUnit {

        WrongPromptUnit(LlmInvoker invoker, PromptTemplateLoader loader, ValidationChain chain,
                        TradingDayCalendar calendar) {
            super(invoker, loader, chain, calendar);
        }

        @Override
        protected String promptId() {
            return "interpret_other";
        }

        @Override
        protected String schemaId() {
            return "interpret_wrong_prompt-v1.json";
        }

        @Override
        protected Map<String, Object> buildFactsPayload(FactBundle facts) {
            return ReferenceFacts.payload();
        }

        @Override
        protected InterpretResult mapOutput(UnitRawOutput raw, FactBundle facts) {
            return new InterpretResult(raw.reportMd(), raw.judgmentBlock(), false, List.of());
        }
    }

    @CapabilityUnit(id = "interpret_no_payload", version = "v1.0", type = UnitType.INTERPRET)
    static class NoPayloadUnit extends AbstractInterpretUnit {

        NoPayloadUnit(LlmInvoker invoker, PromptTemplateLoader loader, ValidationChain chain,
                      TradingDayCalendar calendar) {
            super(invoker, loader, chain, calendar);
        }

        @Override
        protected String promptId() {
            return "interpret_no_payload";
        }

        @Override
        protected String schemaId() {
            return "interpret_no_payload-v1.json";
        }

        @Override
        protected Map<String, Object> buildFactsPayload(FactBundle facts) {
            return Map.of();
        }

        @Override
        protected InterpretResult mapOutput(UnitRawOutput raw, FactBundle facts) {
            return new InterpretResult(raw.reportMd(), raw.judgmentBlock(), false, List.of());
        }
    }

    @CapabilityUnit(id = "interpret_null_output", version = "v1.0", type = UnitType.INTERPRET)
    static class NullOutputUnit extends AbstractInterpretUnit {

        NullOutputUnit(LlmInvoker invoker, PromptTemplateLoader loader, ValidationChain chain,
                       TradingDayCalendar calendar) {
            super(invoker, loader, chain, calendar);
        }

        @Override
        protected String promptId() {
            return "interpret_null_output";
        }

        @Override
        protected String schemaId() {
            return "interpret_null_output-v1.json";
        }

        @Override
        protected Map<String, Object> buildFactsPayload(FactBundle facts) {
            return ReferenceFacts.payload();
        }

        @Override
        protected InterpretResult mapOutput(UnitRawOutput raw, FactBundle facts) {
            return null;
        }
    }
}
