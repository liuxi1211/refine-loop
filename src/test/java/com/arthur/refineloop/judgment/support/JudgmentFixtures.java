package com.arthur.refineloop.judgment.support;

import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentInput;
import com.arthur.refineloop.judgment.model.MethodSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判断域测试夹具：正常判断块、机械降级判断块与对应的存档请求。
 *
 * <p>两个块都按能力详案 3.1/3.2 的<b>真实形状</b>构造（含 {@code unit_id/unit_version/stock_code/trade_date}
 * 身份字段与四类验证规则），这样存档器的装配逻辑与「真实产出」共用一条路径；
 * 用「随手拼的 Map」测存档器会漏掉身份校验、规则类型校验这类最关键的约束。
 */
public final class JudgmentFixtures {

    public static final String TS_CODE = "000001.SZ";

    public static final String TRADE_DATE = "20261126";

    public static final String PIPELINE = "P1";

    public static final String METHOD_VERSION = "M1-v1.0";

    public static final String SCHOOL = "growth_trend";

    public static final String COMPUTE_UNIT = "calc_valuation_percentile";

    public static final String INTERPRET_UNIT = "interpret_valuation_probe";

    private JudgmentFixtures() {
    }

    /** 参与单元两枚：一个 compute（事实）+ 一个 interpret（判断块产出者）。 */
    public static MethodSnapshot method() {
        return new MethodSnapshot(METHOD_VERSION, SCHOOL, List.of(
                new MethodSnapshot.UnitRef(COMPUTE_UNIT, "v1.0", Map.of("data_status", "ok")),
                new MethodSnapshot.UnitRef(INTERPRET_UNIT, "v1.0", Map.of(
                        "dimension", "valuation", "conclusion", "偏低",
                        "facts_used", List.of(factUsage())))));
    }

    /** 正常判断块（L3 已通过的最小完整形状）。 */
    public static Map<String, Object> block() {
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "bullish");
        direction.put("horizon_days", 60);
        direction.put("target_note", "预期相对沪深300 获取超额收益");

        Map<String, Object> thesis = new LinkedHashMap<>();
        thesis.put("bull_core", "营收增速回升 + 估值分位低位");
        thesis.put("bear_core", "行业景气未见拐点");
        thesis.put("current_side", "bull");
        thesis.put("confidence", "中");

        Map<String, Object> keyLevels = new LinkedHashMap<>();
        keyLevels.put("support", List.of(10.85));
        keyLevels.put("resistance", List.of(12.4));
        keyLevels.put("stop_loss", 10.4);
        keyLevels.put("invalidation_condition", "跌破10.40 或 放量滞涨3日");

        Map<String, Object> block = baseIdentity();
        block.put("dimension", "valuation");
        block.put("conclusion", "偏低");
        block.put("confidence", "中");
        block.put("facts_used", List.of(factUsage()));
        block.put("logic_tags", List.of("估值处于历史低位"));
        block.put("data_gaps", List.of());
        block.put("direction", direction);
        block.put("key_levels", keyLevels);
        block.put("thesis", thesis);
        block.put("verification_rules", List.of(
                rule("price_touch", "resistance", Map.of("level", 12.4)),
                rule("price_break", "stop_loss", Map.of("level", 10.4)),
                rule("direction_fail", null, Map.of("expire_at", "2027-01-25"))));
        return block;
    }

    /**
     * 机械降级判断块：与 {@code InterpretResult.degraded} 的形状一致
     * （无 thesis、无 key_levels、无 facts，仅到期重估规则）。
     */
    public static Map<String, Object> degradedBlock() {
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "neutral");
        direction.put("horizon_days", 5);
        direction.put("expire_at", "2026-12-03");
        direction.put("target_note", "降级：仅事实表存档，无 LLM 叙事");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expire_at", "2026-12-03");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("rule_type", "direction_fail");
        rule.put("params", params);
        rule.put("on_hit", "to_verify");

        Map<String, Object> block = baseIdentity();
        block.put("direction", direction);
        block.put("confidence", "低");
        block.put("degraded", true);
        block.put("facts_used", List.of());
        block.put("logic_tags", List.of());
        block.put("data_gaps", List.of("L3 校验重试耗尽：正文出现未包裹数字"));
        block.put("verification_rules", List.of(rule));
        return block;
    }

    /** 正常请求。 */
    public static JudgmentArchiveRequest request(long reportId) {
        return request(TRADE_DATE, TS_CODE, block(), false, List.of(), method(), reportId);
    }

    /** 降级请求。 */
    public static JudgmentArchiveRequest degradedRequest(long reportId) {
        return request(TRADE_DATE, TS_CODE, degradedBlock(), true,
                List.of("L3 校验重试耗尽：正文出现未包裹数字"), method(), reportId);
    }

    public static JudgmentArchiveRequest request(String tradeDate, String tsCode, Map<String, Object> block,
                                                 boolean degraded, List<String> gaps,
                                                 MethodSnapshot method, long reportId) {
        return new JudgmentArchiveRequest(tsCode, tradeDate, PIPELINE, reportId,
                "reports/" + tradeDate + "/" + tsCode + ".md", method,
                new JudgmentInput(block, degraded, gaps));
    }

    /** 深拷贝块（记录型夹具要允许单个用例改一个字段而不污染其它用例）。 */
    public static Map<String, Object> copyOf(Map<String, Object> block) {
        Map<String, Object> copy = new LinkedHashMap<>(block);
        copy.computeIfPresent("direction", (key, value) -> new LinkedHashMap<>((Map<?, ?>) value));
        copy.computeIfPresent("thesis", (key, value) -> new LinkedHashMap<>((Map<?, ?>) value));
        copy.computeIfPresent("key_levels", (key, value) -> new LinkedHashMap<>((Map<?, ?>) value));
        copy.computeIfPresent("verification_rules", (key, value) -> new ArrayList<>((List<?>) value));
        copy.computeIfPresent("data_gaps", (key, value) -> new ArrayList<>((List<?>) value));
        copy.computeIfPresent("logic_tags", (key, value) -> new ArrayList<>((List<?>) value));
        return copy;
    }

    private static Map<String, Object> baseIdentity() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("unit_id", INTERPRET_UNIT);
        block.put("unit_version", "v1.0");
        block.put("stock_code", TS_CODE);
        block.put("trade_date", TRADE_DATE);
        return block;
    }

    private static Map<String, Object> factUsage() {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("metric", "percentile_3y.pe_ttm");
        fact.put("value", 18.5);
        fact.put("unit", "pct");
        return fact;
    }

    private static Map<String, Object> rule(String ruleType, String target, Map<String, Object> params) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("rule_type", ruleType);
        if (target != null) {
            rule.put("target", target);
        }
        rule.put("params", params);
        rule.put("on_hit", "to_verify");
        return rule;
    }
}
