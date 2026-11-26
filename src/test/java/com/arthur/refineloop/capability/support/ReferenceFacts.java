package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：估值事实载荷（含指标叶子 {@code {value, unit}}）与对应的已 seal 事实视图。
 *
 * <p>指标路径刻意做成嵌套结构（{@code valuation.percentile_3y.pe_ttm}），用于验证
 * {@code FactMetrics} 的摊平规则与 facts_used 比对路径。
 */
public final class ReferenceFacts {

    /** 单元 id（与 Citation 单元/事实产出一致）。 */
    public static final String FACT_UNIT_ID = "calc_valuation_percentile";

    public static final String TS_CODE = "000001.SZ";

    public static final String TRADE_DATE = "20261126";

    /** 指标路径常量：测试与夹具共用，避免字符串写错。 */
    public static final String PE_PERCENTILE = "valuation.percentile_3y.pe_ttm";

    public static final String PB_PERCENTILE = "valuation.percentile_3y.pb";

    public static final String PE_CURRENT = "valuation.current.pe_ttm";

    public static final String LOSS_RATIO = "loss_ratio_3y";

    private ReferenceFacts() {
    }

    /** 事实载荷：当前值 + 三年分位 + 亏损样本占比。 */
    public static Map<String, Object> payload() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("pe_ttm", metric(5.2, "倍"));
        current.put("pb", metric(0.58, "倍"));

        Map<String, Object> percentile = new LinkedHashMap<>();
        percentile.put("pe_ttm", metric(18.5, "pct"));
        percentile.put("pb", metric(22.1, "pct"));

        Map<String, Object> valuation = new LinkedHashMap<>();
        valuation.put("current", current);
        valuation.put("percentile_3y", percentile);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data_status", "ok");
        payload.put("valuation", valuation);
        payload.put("loss_ratio_3y", metric(0.02, "ratio"));
        payload.put("memo", "亏损样本已单列，分位按盈利样本计算");
        return payload;
    }

    /** 已 seal 的事实视图（两段式生命周期的第二阶段）。 */
    public static FactBundle bundle() {
        return bundle(TS_CODE, TRADE_DATE);
    }

    public static FactBundle bundle(String tsCode, String tradeDate) {
        FactResult fact = new FactResult(tsCode, FACT_UNIT_ID, "v1.0", "v1.0",
                FactDataStatus.COMPLETE, payload(), List.of("分位基于盈利样本"));
        return FactBundle.builder(tsCode, tradeDate, "run-t8").put(fact).seal();
    }

    /** 指标叶子：{@code {"value":…, "unit":…}}。 */
    public static Map<String, Object> metric(double value, String unit) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("value", value);
        leaf.put("unit", unit);
        return leaf;
    }
}
