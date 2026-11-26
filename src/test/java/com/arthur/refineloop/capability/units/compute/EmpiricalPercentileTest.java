package com.arthur.refineloop.capability.units.compute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("经验 CDF 与 nearest-rank：分位口径")
class EmpiricalPercentileTest {

    @Test
    @DisplayName("CDF：≤ 当前值的样本占比 × 100（含当日样本）")
    void cdfCountsSamplesNotGreaterThanCurrent() {
        List<BigDecimal> samples = decimals("1", "2", "3", "4", "5");

        assertThat(EmpiricalPercentile.cdf(samples, new BigDecimal("3"))).isEqualByComparingTo("60.00");
        assertThat(EmpiricalPercentile.cdf(samples, new BigDecimal("1"))).isEqualByComparingTo("20.00");
        assertThat(EmpiricalPercentile.cdf(samples, new BigDecimal("5"))).isEqualByComparingTo("100.00");
        assertThat(EmpiricalPercentile.cdf(samples, new BigDecimal("0.5"))).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("CDF：并列样本全部计入（15 出现 3 次，全算 ≤15）")
    void cdfCountsAllTies() {
        List<BigDecimal> samples = decimals("10", "15", "15", "15", "20");

        assertThat(EmpiricalPercentile.cdf(samples, new BigDecimal("15"))).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("CDF：nosample 或当日值缺失返回 null（缺失不是 0）")
    void cdfReturnsNullOnMissingInput() {
        assertThat(EmpiricalPercentile.cdf(List.of(), new BigDecimal("1"))).isNull();
        assertThat(EmpiricalPercentile.cdf(null, new BigDecimal("1"))).isNull();
        assertThat(EmpiricalPercentile.cdf(decimals("1", "2"), null)).isNull();
    }

    @Test
    @DisplayName("nearest-rank：取样本中的实际值，不插值")
    void nearestRankPicksRealSampleValues() {
        List<BigDecimal> samples = decimals("1", "2", "3", "4");

        assertThat(EmpiricalPercentile.nearestRank(samples, 10)).isEqualByComparingTo("1");
        assertThat(EmpiricalPercentile.nearestRank(samples, 25)).isEqualByComparingTo("1");
        assertThat(EmpiricalPercentile.nearestRank(samples, 50)).isEqualByComparingTo("2");
        assertThat(EmpiricalPercentile.nearestRank(samples, 75)).isEqualByComparingTo("3");
        assertThat(EmpiricalPercentile.nearestRank(samples, 100)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("nearest-rank：单样本时任何分位点都是它本身")
    void nearestRankWithSingleSample() {
        List<BigDecimal> samples = decimals("7.5");

        assertThat(EmpiricalPercentile.nearestRank(samples, 1)).isEqualByComparingTo("7.5");
        assertThat(EmpiricalPercentile.nearestRank(samples, 90)).isEqualByComparingTo("7.5");
    }

    @Test
    @DisplayName("nearest-rank：空样本返回 null，非法分位点抛参数异常")
    void nearestRankRejectsInvalidInput() {
        assertThat(EmpiricalPercentile.nearestRank(List.of(), 50)).isNull();
        assertThatThrownBy(() -> EmpiricalPercentile.nearestRank(decimals("1", "2"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~100");
        assertThatThrownBy(() -> EmpiricalPercentile.nearestRank(decimals("1", "2"), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
