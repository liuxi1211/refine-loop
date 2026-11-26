package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.capability.support.ReferenceFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FactMetrics：事实载荷摊平与数值归一")
class FactMetricsTest {

    @Test
    @DisplayName("嵌套指标摊平为点号路径，单位随值保留")
    void flattensNestedMetrics() {
        Map<String, FactMetrics.Metric> metrics = FactMetrics.flatten(ReferenceFacts.payload());

        assertThat(metrics).containsKeys(
                ReferenceFacts.PE_PERCENTILE, ReferenceFacts.PB_PERCENTILE,
                ReferenceFacts.PE_CURRENT, ReferenceFacts.LOSS_RATIO);
        FactMetrics.Metric pe = metrics.get(ReferenceFacts.PE_PERCENTILE);
        assertThat(pe.value()).isEqualByComparingTo("18.5");
        assertThat(pe.unit()).isEqualTo("pct");
        assertThat(pe.hasUnit()).isTrue();
    }

    @Test
    @DisplayName("列表元素用下标路径；裸数字与字符串不构成指标")
    void flattensListsAndIgnoresScalars() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supports", List.of(ReferenceFacts.metric(10.85, "元"), ReferenceFacts.metric(10.4, "元")));
        payload.put("note", "说明文本");
        payload.put("count", 3);

        Map<String, FactMetrics.Metric> metrics = FactMetrics.flatten(payload);

        assertThat(metrics).containsOnlyKeys("supports[0]", "supports[1]");
        assertThat(metrics.get("supports[1]").value()).isEqualByComparingTo("10.4");
    }

    @Test
    @DisplayName("数值归一覆盖 Integer/Long/Double/BigDecimal/字符串，非法值返回 null")
    void normalizesNumberTypes() {
        assertThat(FactMetrics.toBigDecimal(18)).isEqualByComparingTo("18");
        assertThat(FactMetrics.toBigDecimal(18L)).isEqualByComparingTo("18");
        assertThat(FactMetrics.toBigDecimal(18.5d)).isEqualByComparingTo("18.5");
        assertThat(FactMetrics.toBigDecimal(new BigDecimal("18.50"))).isEqualByComparingTo("18.5");
        assertThat(FactMetrics.toBigDecimal(" 18.5 ")).isEqualByComparingTo("18.5");
        assertThat(FactMetrics.toBigDecimal("abc")).isNull();
        assertThat(FactMetrics.toBigDecimal(null)).isNull();
        assertThat(FactMetrics.toBigDecimal(true)).isNull();
    }

    @Test
    @DisplayName("指标叶子缺 unit 时 unit 为 null，hasUnit=false")
    void missingUnitIsFlagged() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("no_unit", Map.of("value", 1.0));

        FactMetrics.Metric metric = FactMetrics.flatten(payload).get("no_unit");

        assertThat(metric.hasUnit()).isFalse();
        assertThat(metric.hasValue()).isTrue();
    }

    @Test
    @DisplayName("空载荷返回空表，不抛异常")
    void emptyPayloadYieldsEmptyMap() {
        assertThat(FactMetrics.flatten(null)).isEmpty();
        assertThat(FactMetrics.flatten(Map.of())).isEmpty();
    }
}
