package com.arthur.refineloop.capability.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解读单元的产出：Markdown 报告 + 结构化判断块。
 *
 * <p>{@code degraded=true} 表示本次为降级产出（数据不足/校验失败），
 * 降级时 {@code judgmentBlock} 仍应存在，保证 emitJudgment 走同一条消费路径。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@code judgmentBlock} 的键顺序即生成顺序（用 {@link LinkedHashMap} 保留），
 *       让落库 JSON 可稳定 diff；浅层不可变、嵌套结构由构造方保证只读；</li>
 *   <li>降级块只能经 {@link #degraded(DegradedJudgment)} 产生，禁止业务单元自己拼；
 *       这样「降级判断长什么样」只有一个事实源，M3 统计过滤 {@code degraded=true} 才有意义。</li>
 * </ul>
 *
 * @param reportMd      报告正文
 * @param judgmentBlock 结构化判断块
 * @param degraded      是否降级
 * @param dataGaps      数据缺口说明
 */
public record InterpretResult(
        String reportMd,
        Map<String, Object> judgmentBlock,
        boolean degraded,
        List<String> dataGaps) {

    /** 降级判断默认有效期（交易日）：短窗口，逼出重估而不是长期挂着无效判断。 */
    public static final int DEGRADED_HORIZON_TRADING_DAYS = 5;

    public InterpretResult {
        reportMd = reportMd == null ? "" : reportMd;
        judgmentBlock = judgmentBlock == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(judgmentBlock));
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    /**
     * 生成机械降级判断（框架产出，非 LLM）。
     *
     * <p>字段对齐能力详案 3.2 主判断 Schema 的骨架部分：{@code direction=neutral}、
     * {@code confidence=低}、{@code degraded=true}、默认 5 个交易日有效期、
     * {@code verification_rules} 仅一条到期重估 {@code direction_fail}，不含任何 LLM 叙事。
     * {@code judgment_id}/{@code method}/{@code benchmark} 属存档期字段，由判断存档器补齐。
     *
     * @param spec 降级依据（单元、标的、到期日、原因）
     * @return 降级结果：{@code reportMd} 为空、{@code degraded=true}
     */
    public static InterpretResult degraded(DegradedJudgment spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        List<Object> reasonList = new ArrayList<>(spec.reasons());
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "neutral");
        direction.put("horizon_days", DEGRADED_HORIZON_TRADING_DAYS);
        direction.put("expire_at", spec.expireAt());
        direction.put("target_note", "降级：仅事实表存档，无 LLM 叙事");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expire_at", spec.expireAt());
        Map<String, Object> expireRule = new LinkedHashMap<>();
        expireRule.put("rule_type", "direction_fail");
        expireRule.put("params", params);
        expireRule.put("on_hit", "to_verify");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("unit_id", spec.unitId());
        block.put("unit_version", spec.unitVersion());
        block.put("stock_code", spec.stockCode());
        block.put("trade_date", spec.tradeDate());
        block.put("direction", direction);
        block.put("confidence", "低");
        block.put("degraded", true);
        block.put("facts_used", List.of());
        block.put("logic_tags", List.of());
        block.put("data_gaps", reasonList);
        block.put("verification_rules", List.of(expireRule));
        return new InterpretResult("", block, true, spec.reasons());
    }
}
