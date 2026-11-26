package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.runtime.llm.AbstractInterpretUnit;
import com.arthur.refineloop.capability.runtime.llm.PromptTemplateLoader;
import com.arthur.refineloop.capability.runtime.llm.LlmInvoker;
import com.arthur.refineloop.capability.runtime.llm.UnitRawOutput;
import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;

import java.util.List;
import java.util.Map;

/**
 * 测试夹具：最小可用的解读单元（T8 验收用「mock 一个最小 interpret 单元」）。
 *
 * <p>它只做四个差异点，业务语义为零；同时充当 T11 联调与 T14 单元开发的骨架参照：
 * 如果这个类薄到只剩 4 个方法，说明模板真的把生命周期收干净了。
 */
@CapabilityUnit(id = "interpret_reference", version = "v1.0", type = UnitType.INTERPRET,
        modelProfile = "fast")
public class ReferenceInterpretUnit extends AbstractInterpretUnit {

    public ReferenceInterpretUnit(LlmInvoker llmInvoker, PromptTemplateLoader promptLoader,
                                  ValidationChain validationChain, TradingDayCalendar calendar) {
        super(llmInvoker, promptLoader, validationChain, calendar);
    }

    @Override
    protected String promptId() {
        return "interpret_reference";
    }

    @Override
    protected String schemaId() {
        return "interpret_reference-v1.json";
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
