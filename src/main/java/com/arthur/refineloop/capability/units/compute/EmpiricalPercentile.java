package com.arthur.refineloop.capability.units.compute;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 经验 CDF 分位与 nearest-rank 分位区间：估值分位单元的唯一数值实现。
 *
 * <p><b>为什么不用正态假设</b>（能力详案 5.4 规则 1）：A 股 PE 分布是典型右偏长尾，
 * 用均值/标准差换算 z-score 会把「尾部极端值」算成高概率，结论方向就反了。经验 CDF 只依赖样本本身。
 *
 * <p><b>口径（写进 SPEC，改口径 = 单元升版）</b>：
 * <ul>
 *   <li>分位值 = {@code 样本中 ≤ 当前值的个数 / 样本总数 × 100}，保留 {@value #PERCENTILE_SCALE} 位，
 *       含当日样本（当日值本身一定计入，因此全样本最大值分位为 100、最小值为一个正数而非 0）；</li>
 *   <li>区间值用 nearest-rank：第 p 百分位取升序样本的第 {@code ceil(p×n/100)} 个（1-based），
 *       不使用插值——插值会产出样本中不存在的 PE 值，与「事实表只放可溯源数字」的原则冲突。</li>
 * </ul>
 *
 * <p><b>不变量</b>：入参样本列表必须已升序（由调用方排序，避免每次二分都重复排序）；
 * 空列表或 null 一律返回 {@code null}（缺失语义，不用 0 顶替）。
 */
public final class EmpiricalPercentile {

    /** 分位值保留位数（与详案 5.4 示例的 18.5 同量级）。 */
    public static final int PERCENTILE_SCALE = 2;

    /** 区间值保留位数（价格/比率口径 DECIMAL(20,4)）。 */
    public static final int BAND_SCALE = 4;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private EmpiricalPercentile() {
    }

    /**
     * 经验 CDF：当前值在升序样本中的排名百分比。
     *
     * @param ascending 升序样本（非空元素）
     * @param current   当前值
     * @return 0~100 的百分数；样本为空或当前值为 null 时返回 null
     */
    public static BigDecimal cdf(List<BigDecimal> ascending, BigDecimal current) {
        if (current == null || ascending == null || ascending.isEmpty()) {
            return null;
        }
        int notGreater = 0;
        for (BigDecimal sample : ascending) {
            if (sample != null && sample.compareTo(current) <= 0) {
                notGreater++;
            }
        }
        return BigDecimal.valueOf(notGreater)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(ascending.size()), PERCENTILE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * nearest-rank：升序样本的第 {@code percentile} 百分位值。
     *
     * @param ascending  升序样本（非空元素）
     * @param percentile 1~100
     * @return 样本中的实际值（保留 {@value #BAND_SCALE} 位）；样本为空返回 null
     */
    public static BigDecimal nearestRank(List<BigDecimal> ascending, int percentile) {
        if (ascending == null || ascending.isEmpty()) {
            return null;
        }
        if (percentile <= 0 || percentile > 100) {
            throw new IllegalArgumentException("分位点必须为 1~100（当前 " + percentile + "）");
        }
        int size = ascending.size();
        long rank = ((long) percentile * size + 99) / 100;      // ceil(p×n/100)，整数运算避免浮点误差
        int index = (int) Math.min(Math.max(rank - 1, 0), size - 1L);
        BigDecimal value = ascending.get(index);
        return value == null ? null : value.setScale(BAND_SCALE, RoundingMode.HALF_UP);
    }
}
