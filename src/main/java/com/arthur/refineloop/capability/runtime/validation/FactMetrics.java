package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.common.util.Strings;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实载荷摊平：把 {@code buildFactsPayload} 的嵌套结构变成「指标路径 → 数值 + 单位」的索引。
 *
 * <p><b>指标叶子约定</b>（单元开发者必须遵守，否则 {@code facts_used} 会被判成「未声明单位」）：
 * <pre>{@code
 * {"percentile_3y": {"pe_ttm": {"value": 18.5, "unit": "pct"}}}   → 指标路径 percentile_3y.pe_ttm
 * }</pre>
 * 含 {@code value} 键的 Map 即指标叶子；其余 Map 递归为 {@code parent.child}，List 递归为 {@code parent[i]}。
 * 裸数字/字符串只作 prompt 上下文，不构成指标（无法携带单位，也就无法参与事实比对）。
 *
 * <p>为什么把单位一起索引：能力详案 3.3 契约 9 要求数值比对必须核对 {@code unit}，
 * 单位不符视为失败——「18.5%」和「18.5 倍」是完全不同的事实，靠名字区分不了。
 */
public final class FactMetrics {

    /** 指标值键名。 */
    public static final String VALUE_KEY = "value";

    /** 指标单位键名。 */
    public static final String UNIT_KEY = "unit";

    private FactMetrics() {
    }

    /**
     * 一个指标事实。
     *
     * @param value 数值；解析失败为 null（说明该叶子不是合法指标）
     * @param unit  单位（pct/元/倍…）；可为 null（说明事实未声明单位）
     */
    public record Metric(BigDecimal value, String unit) {

        public boolean hasValue() {
            return value != null;
        }

        public boolean hasUnit() {
            return unit != null && !unit.isBlank();
        }
    }

    /**
     * 摊平事实载荷。
     *
     * @param payload 事实载荷（可为 null）
     * @return 指标路径 → 指标（保持声明顺序）
     */
    public static Map<String, Metric> flatten(Map<String, Object> payload) {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return metrics;
        }
        payload.forEach((key, value) -> collect(key, value, metrics));
        return metrics;
    }

    /**
     * 数值归一：兼容 JSON 反序列化出的 Integer/Long/Double/BigDecimal 与数值字符串。
     *
     * @param raw 原始值
     * @return 数值；无法解析返回 null
     */
    public static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Double || raw instanceof Float) {
            double value = ((Number) raw).doubleValue();
            return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void collect(String path, Object node, Map<String, Metric> metrics) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey(VALUE_KEY)) {
                metrics.put(path, toMetric(map));
                return;
            }
            map.forEach((key, value) -> collect(path + "." + key, value, metrics));
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collect(path + "[" + i + "]", list.get(i), metrics);
            }
        }
    }

    private static Metric toMetric(Map<?, ?> map) {
        String unit = Strings.trimToNull(asText(map.get(UNIT_KEY)));
        return new Metric(toBigDecimal(map.get(VALUE_KEY)), unit);
    }

    private static String asText(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
