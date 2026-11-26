package com.arthur.refineloop.capability.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InterpretResult：机械降级判断")
class InterpretResultTest {

    @Test
    @DisplayName("降级块字段齐全：neutral + 低 + degraded + 5 交易日 + 单条 direction_fail")
    void degradedBlockHasFullSkeleton() {
        InterpretResult result = InterpretResult.degraded(spec(List.of("L3 校验重试耗尽：facts_used 编造指标")));

        assertThat(result.degraded()).isTrue();
        assertThat(result.reportMd()).isEmpty();
        assertThat(result.dataGaps()).containsExactly("L3 校验重试耗尽：facts_used 编造指标");

        Map<String, Object> block = result.judgmentBlock();
        assertThat(block)
                .containsEntry("unit_id", "interpret_reference")
                .containsEntry("unit_version", "v1.0")
                .containsEntry("stock_code", "000001.SZ")
                .containsEntry("trade_date", "20261126")
                .containsEntry("confidence", "低")
                .containsEntry("degraded", true)
                .containsEntry("facts_used", List.of())
                .containsEntry("logic_tags", List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) block.get("direction");
        assertThat(direction)
                .containsEntry("type", "neutral")
                .containsEntry("horizon_days", 5)
                .containsEntry("expire_at", "2026-12-03");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) block.get("verification_rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).containsEntry("rule_type", "direction_fail")
                .containsEntry("on_hit", "to_verify");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) rules.get(0).get("params");
        assertThat(params).containsEntry("expire_at", "2026-12-03");
    }

    @Test
    @DisplayName("data_gaps 与 reasons 同源，且块键序稳定")
    void dataGapsMirrorReasons() {
        InterpretResult result = InterpretResult.degraded(spec(List.of("a", "b")));

        @SuppressWarnings("unchecked")
        List<Object> gaps = (List<Object>) result.judgmentBlock().get("data_gaps");
        assertThat(gaps).containsExactly("a", "b");
        assertThat(result.judgmentBlock().keySet())
                .containsExactly("unit_id", "unit_version", "stock_code", "trade_date", "direction",
                        "confidence", "degraded", "facts_used", "logic_tags", "data_gaps",
                        "verification_rules");
    }

    @Test
    @DisplayName("原因为空时兜底写一条，避免 data_gaps 空着不可解释")
    void emptyReasonsFallback() {
        InterpretResult result = InterpretResult.degraded(new DegradedJudgment(
                "interpret_reference", "v1.0", "000001.SZ", "20261126", "2026-12-03", List.of()));

        assertThat(result.dataGaps()).hasSize(1);
        assertThat(result.dataGaps().get(0)).contains("降级");
    }

    @Test
    @DisplayName("非法入参在构造期拒绝")
    void rejectsInvalidSpec() {
        assertThatThrownBy(() -> new DegradedJudgment(
                "interpret_reference", "v1.0", "000001.SZ", "2026-11-26", "2026-12-03", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyyMMdd");
        assertThatThrownBy(() -> new DegradedJudgment(
                "interpret_reference", "v1.0", "000001.SZ", "20261126", "20261203", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> InterpretResult.degraded(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec");
    }

    private DegradedJudgment spec(List<String> reasons) {
        return new DegradedJudgment("interpret_reference", "v1.0", "000001.SZ",
                "20261126", "2026-12-03", reasons);
    }
}
