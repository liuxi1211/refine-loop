package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.runtime.validation.ValidationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：合法的判断块（可改字段后用于各类失败用例）。
 *
 * <p>为什么用可变 {@link LinkedHashMap}：用例通常只改一两处（篡改数值、越界标签…），
 * 基于基线做局部覆盖比每次手写整块更能表达「只差这一点」。
 */
public final class JudgmentBlocks {

    public static final String UNIT_ID = "interpret_reference";

    public static final String UNIT_VERSION = "v1.0";

    public static final String SCHEMA_ID = "interpret_reference-v1.json";

    private JudgmentBlocks() {
    }

    /** 合法判断块基线（与 {@link ReferenceFacts#bundle()} 对齐）。 */
    public static Map<String, Object> base() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("unit_id", UNIT_ID);
        block.put("unit_version", UNIT_VERSION);
        block.put("stock_code", ReferenceFacts.TS_CODE);
        block.put("trade_date", ReferenceFacts.TRADE_DATE);
        block.put("dimension", "valuation");
        block.put("conclusion", "偏低");
        block.put("confidence", "中");
        block.put("facts_used", factsUsed());
        block.put("logic_tags", new ArrayList<>(List.of("估值处于历史低位")));
        block.put("data_gaps", new ArrayList<>(List.of("无")));
        return block;
    }

    /** 两条引用（分位 + 当前值）。 */
    public static List<Map<String, Object>> factsUsed() {
        List<Map<String, Object>> used = new ArrayList<>();
        used.add(reference(ReferenceFacts.PE_PERCENTILE, 18.5, "pct"));
        used.add(reference(ReferenceFacts.PB_PERCENTILE, 22.1, "pct"));
        return used;
    }

    public static Map<String, Object> reference(String metric, Object value, String unit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("metric", metric);
        item.put("value", value);
        item.put("unit", unit);
        return item;
    }

    /** 正文中的溯源标记文本。 */
    public static String marker(String metric, String value) {
        return "[[" + metric + "|" + value + "]]";
    }

    /** 校正默认上下文（默认 schemaId、默认事实载荷与报告正文）。 */
    public static ValidationContext context(Map<String, Object> block, String report) {
        return context(block, ReferenceFacts.payload(), report, SCHEMA_ID);
    }

    public static ValidationContext context(Map<String, Object> block, String report, String schemaId) {
        return context(block, ReferenceFacts.payload(), report, schemaId);
    }

    public static ValidationContext context(Map<String, Object> block, Map<String, Object> payload,
                                            String report, String schemaId) {
        return new ValidationContext(ReferenceFacts.bundle(), payload, block, report, null,
                UNIT_ID, UNIT_VERSION, schemaId);
    }

    /** 输出解析失败的上下文（责任链应短路）。 */
    public static ValidationContext parseErrorContext(String parseError) {
        return new ValidationContext(ReferenceFacts.bundle(), ReferenceFacts.payload(), Map.of(), "",
                parseError, UNIT_ID, UNIT_VERSION, SCHEMA_ID);
    }
}
