package com.arthur.refineloop.capability.runtime.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ReportGenerator：模板渲染 + 基座调用")
class ReportGeneratorTest {

    @Test
    @DisplayName("默认 fast 档，渲染后的 prompt 传给 LlmInvoker")
    void generateUsesRenderedPromptAndDefaultProfile() {
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        LlmInvoker invoker = mock(LlmInvoker.class);
        when(loader.load("daily_summary", "v1")).thenReturn("today {{tradeDate}}");
        when(loader.render(anyString(), anyMap())).thenReturn("today 20261126");
        LlmCallResult callResult = new LlmCallResult("一句话", "test-model",
                10, 20, 30, new BigDecimal("0.00005"), 1, 12L);
        when(invoker.invoke(any())).thenReturn(callResult);
        ReportGenerator generator = new ReportGenerator(loader, invoker);

        ReportGenerator.ReportOutput output = generator.generate(
                new ReportGenerator.ReportRequest(
                        "daily_summary", "v1", "daily_summary", "run-1", null,
                        Map.of("tradeDate", "20261126")));

        assertThat(output.text()).isEqualTo("一句话");
        assertThat(output.call()).isSameAs(callResult);
        ArgumentCaptor<LlmCallSpec> captor = ArgumentCaptor.forClass(LlmCallSpec.class);
        verify(invoker).invoke(captor.capture());
        LlmCallSpec spec = captor.getValue();
        assertThat(spec.profile()).isEqualTo("fast");
        assertThat(spec.systemPrompt()).isEqualTo("today 20261126");
        assertThat(spec.unitId()).isEqualTo("daily_summary");
        assertThat(spec.runId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("显式 strong 档透传")
    void generateUsesRequestedProfile() {
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        LlmInvoker invoker = mock(LlmInvoker.class);
        when(loader.load("daily_summary", "v1")).thenReturn("t");
        when(loader.render(anyString(), anyMap())).thenReturn("t");
        when(invoker.invoke(any())).thenReturn(new LlmCallResult(
                "x", "m", 0, 0, 0, BigDecimal.ZERO, 1, 1L));
        ReportGenerator generator = new ReportGenerator(loader, invoker);

        generator.generate(new ReportGenerator.ReportRequest(
                "daily_summary", "v1", "daily_summary", null, "strong", Map.of()));

        ArgumentCaptor<LlmCallSpec> captor = ArgumentCaptor.forClass(LlmCallSpec.class);
        verify(invoker).invoke(captor.capture());
        assertThat(captor.getValue().profile()).isEqualTo("strong");
    }
}
