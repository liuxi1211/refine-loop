package com.arthur.refineloop.judgment.model;

import java.math.BigDecimal;

/**
 * 判断存档时的行情基准快照：后续方向验证只能用这份起点，禁止事后回查替换。
 *
 * <p>口径：个股 {@code adjustedClose = close × adjFactor}（应对除权），基准指数直接使用 close。
 * 三个值都必填；缺任一项都不能生成可验证的方向判断。
 *
 * @param stockClose 个股当日不复权收盘价（元）
 * @param stockAdjFactor 个股当日复权因子
 * @param benchmarkClose 基准指数当日收盘点位
 */
public record MarketSnapshot(BigDecimal stockClose,
                              BigDecimal stockAdjFactor,
                              BigDecimal benchmarkClose) {

    public MarketSnapshot {
        requirePositive(stockClose, "个股 reference close");
        requirePositive(stockAdjFactor, "个股 reference adj_factor");
        requirePositive(benchmarkClose, "基准指数 reference close");
    }

    private static void requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(label + " 必须为正数（当前 " + value + "）");
        }
    }
}
