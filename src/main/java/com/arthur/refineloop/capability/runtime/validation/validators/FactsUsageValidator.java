package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.FactMetrics;
import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.common.util.Strings;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ② facts_used 与事实载荷的「指标名 + 数值 + 单位」三重比对。
 *
 * <p>三层防线各拦一类错误：
 * <ul>
 *   <li><b>指标名不存在</b> = 编造指标（模型凭空引用了事实表里没有的东西）；</li>
 *   <li><b>数值不一致</b> = 篡改数字（相对误差 &gt; 0.1% 即失败，容忍浮点表示差异）；</li>
 *   <li><b>单位不符/缺失</b> = 口径错位（18.5% 与 18.5 倍是两件事，靠名字区分不了）。</li>
 * </ul>
 *
 * <p>数值比对必须走 {@link BigDecimal} 数值语义（能力详案 3.3 契约 9）：禁止字符串比对，
 * 否则 {@code 18.50} 与 {@code 18.5} 会被误判为篡改。
 *
 * <p>不变量：只读不写；错误路径精确到 {@code $.facts_used[i].字段}，模型可据此逐条修正。
 */
public class FactsUsageValidator implements JudgmentValidator {

    /** 校验器名。 */
    public static final String NAME = "facts_usage";

    /** 允许的相对误差上限：0.1%（能力详案 §4.2.5 口径）。 */
    public static final BigDecimal MAX_RELATIVE_ERROR = new BigDecimal("0.001");

    private static final String FACTS_USED = "facts_used";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ValidationError> validate(ValidationContext context) {
        Object raw = context.judgmentBlock().get(FACTS_USED);
        if (!(raw instanceof List<?> used)) {
            return List.of(new ValidationError(NAME, "$." + FACTS_USED, "type",
                    FACTS_USED + " 必须是数组，每项 {metric, value, unit}"));
        }
        if (used.isEmpty()) {
            return List.of(new ValidationError(NAME, "$." + FACTS_USED, "empty",
                    FACTS_USED + " 不能为空：任何结论都必须引用至少一条事实"));
        }
        Map<String, FactMetrics.Metric> metrics = FactMetrics.flatten(context.factsPayload());
        List<ValidationError> errors = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        for (int i = 0; i < used.size(); i++) {
            checkEntry(used.get(i), "$." + FACTS_USED + "[" + i + "]", metrics, referenced, errors);
        }
        return errors;
    }

    private void checkEntry(Object item, String basePath, Map<String, FactMetrics.Metric> metrics,
                            Set<String> referenced, List<ValidationError> errors) {
        if (!(item instanceof Map<?, ?> entry)) {
            errors.add(new ValidationError(NAME, basePath, "type",
                    "每项必须是 {metric, value, unit} 对象"));
            return;
        }
        String metric = Strings.trimToNull(asText(entry.get("metric")));
        if (metric == null) {
            errors.add(new ValidationError(NAME, basePath + ".metric", "required",
                    "metric 不能为空（必须是事实载荷中的指标路径）"));
            return;
        }
        if (!referenced.add(metric)) {
            errors.add(new ValidationError(NAME, basePath + ".metric", "duplicate_metric",
                    "指标重复引用：" + metric + "（同一指标只允许出现一次）"));
        }
        FactMetrics.Metric fact = metrics.get(metric);
        if (fact == null) {
            errors.add(new ValidationError(NAME, basePath + ".metric", "fabricated_metric",
                    "事实载荷中不存在指标 " + metric + "（禁止引用事实表以外的指标）"));
            return;
        }
        if (!fact.hasUnit()) {
            errors.add(new ValidationError(NAME, basePath + ".metric", "missing_fact_unit",
                    "指标 " + metric + " 在事实载荷中未声明 unit，无法核对口径"));
        }
        checkValue(entry.get("value"), basePath, metric, fact, errors);
        checkUnit(entry.get("unit"), basePath, metric, fact, errors);
    }

    private void checkValue(Object rawValue, String basePath, String metric,
                            FactMetrics.Metric fact, List<ValidationError> errors) {
        BigDecimal declared = FactMetrics.toBigDecimal(rawValue);
        if (declared == null) {
            errors.add(new ValidationError(NAME, basePath + ".value", "not_a_number",
                    "value 必须是数值（当前 " + rawValue + "）"));
            return;
        }
        if (!fact.hasValue()) {
            errors.add(new ValidationError(NAME, basePath + ".value", "fact_not_numeric",
                    "指标 " + metric + " 的事实值不是数值，无法比对"));
            return;
        }
        if (!withinTolerance(fact.value(), declared)) {
            errors.add(new ValidationError(NAME, basePath + ".value", "value_mismatch",
                    "指标 " + metric + " 数值被篡改：事实值 " + fact.value().toPlainString()
                            + "，判断块写 " + declared.toPlainString()
                            + "（允许相对误差 ≤ " + MAX_RELATIVE_ERROR.movePointRight(2).toPlainString() + "%）"));
        }
    }

    private void checkUnit(Object rawUnit, String basePath, String metric,
                           FactMetrics.Metric fact, List<ValidationError> errors) {
        String declared = Strings.trimToNull(asText(rawUnit));
        if (declared == null) {
            errors.add(new ValidationError(NAME, basePath + ".unit", "required",
                    "unit 不能为空（指标 " + metric + " 的口径为 " + fact.unit() + "）"));
            return;
        }
        if (fact.hasUnit() && !fact.unit().equalsIgnoreCase(declared)) {
            errors.add(new ValidationError(NAME, basePath + ".unit", "unit_mismatch",
                    "指标 " + metric + " 单位不符：事实口径为 " + fact.unit() + "，判断块写 " + declared));
        }
    }

    /**
     * 相对误差是否在容忍范围内。
     *
     * <p>事实值为 0 时改用绝对相等（0 的相对误差没有定义，任何非零值都必须失败）。
     */
    static boolean withinTolerance(BigDecimal actual, BigDecimal declared) {
        BigDecimal difference = actual.subtract(declared).abs();
        if (actual.compareTo(BigDecimal.ZERO) == 0) {
            return difference.compareTo(BigDecimal.ZERO) == 0;
        }
        BigDecimal relative = difference.divide(actual.abs(), MathContext.DECIMAL64);
        return relative.compareTo(MAX_RELATIVE_ERROR) <= 0;
    }

    private static String asText(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
