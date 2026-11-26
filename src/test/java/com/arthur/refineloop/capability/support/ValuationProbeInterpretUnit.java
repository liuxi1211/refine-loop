package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.llm.AbstractInterpretUnit;
import com.arthur.refineloop.capability.runtime.llm.LlmInvoker;
import com.arthur.refineloop.capability.runtime.llm.PromptTemplateLoader;
import com.arthur.refineloop.capability.runtime.llm.UnitRawOutput;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.capability.units.compute.ValuationFactSchema;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T11 最小闭环用的临时解读单元（真实单元是 T14 的 {@code interpret_valuation}）。
 *
 * <p><b>为什么必须有它</b>：最小闭环要验证的是「compute 事实 → interpret 解读 → L3 校验 →
 * 判断存档」这条链路，而 T14 的正式单元还没开发。用一个<b>只写四个差异点</b>的探针单元跑通链路，
 * 既证明模板真的把生命周期收干净了（子类薄到只剩业务），又不会让 T14 被迫提前实现方法论。
 *
 * <p><b>与正式单元的差别（刻意）</b>：
 * <ul>
 *   <li>不做「口径适配」判断（正式单元要结合业务速览选 PE/PS/PB 口径），只按分位与亏损占比给结论；</li>
 *   <li>不消费业务速览等上游结构化摘要（T14 的 interpret 单元才有跨单元依赖）；</li>
 *   <li>输出仍是完整主判断形状（direction/thesis/key_levels/verification_rules），
 *       这样 {@code emitJudgment} 可以当成真实产物消费。</li>
 * </ul>
 *
 * <p><b>生命周期在哪里</b>：全部在 {@link AbstractInterpretUnit}。本类没有重试、没有降级、没有校验代码——
 * 这正是 T8 模板的验收点，也是 T14 的开发样板。
 *
 * <p><b>资源</b>（test classpath）：{@code prompts/interpret_valuation_probe/v1.md} +
 * {@code schema/interpret_valuation_probe-v1.json}。放在 test 资源里是刻意的：它是临时单元，
 * 验收后删除（T14 上线时连同本类一起移除），不能让临时 prompt 混进生产资源目录被误引用。
 */
@CapabilityUnit(id = ValuationProbeInterpretUnit.UNIT_ID, version = "v1.0",
        type = UnitType.INTERPRET, modelProfile = "fast")
public class ValuationProbeInterpretUnit extends AbstractInterpretUnit {

    /** 单元 id：与 prompt/schema 资源目录名一致（模板会校验三者自洽）。 */
    public static final String UNIT_ID = "interpret_valuation_probe";

    public ValuationProbeInterpretUnit(LlmInvoker llmInvoker, PromptTemplateLoader promptLoader,
                                       ValidationChain validationChain, TradingDayCalendar calendar) {
        super(llmInvoker, promptLoader, validationChain, calendar);
    }

    @Override
    protected String promptId() {
        return UNIT_ID;
    }

    @Override
    protected String schemaId() {
        return UNIT_ID + "-v1.json";
    }

    /**
     * 喂给模型的事实：只给估值事实，且<b>整体挂到 {@code valuation} 键下</b>。
     *
     * <p>挂一层的原因：指标路径变成 {@code valuation.percentile_3y.pe_ttm}，与未来正式单元
     * （消费多个上游事实：business_overview / fundamental_quality / valuation_fact…）的载荷形状一致，
     * 迁移到 T14 时 prompt 与校验规则不用重写路径口径。
     */
    @Override
    protected Map<String, Object> buildFactsPayload(FactBundle facts) {
        FactResult valuation = facts.fact(ValuationFactSchema.UNIT_ID);
        if (valuation == null) {
            // 上游事实缺失属编排缺陷（S1 没跑或跑失败），不是本标的降级
            throw ApiException.of(CapabilityErrorCode.FACT_INVALID,
                    "缺少上游事实 " + ValuationFactSchema.UNIT_ID + "（stock=" + facts.tsCode() + "）");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stock_code", facts.tsCode());
        payload.put("trade_date", facts.tradeDate());
        payload.put("valuation", valuation.payload());
        return payload;
    }

    @Override
    protected InterpretResult mapOutput(UnitRawOutput raw, FactBundle facts) {
        return new InterpretResult(raw.reportMd(), raw.judgmentBlock(), false, List.of());
    }
}
