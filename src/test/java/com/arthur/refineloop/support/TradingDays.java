package com.arthur.refineloop.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 测试用交易日规则：跳过周六/周日（不含法定节假日表）。
 *
 * <p>为什么需要它：判断的 {@code horizon_days} 口径是<b>交易日</b>，而单测没有回填
 * {@code trade_cal}。用一个明确的「仅跳周末」规则，可以让夹具里声明的 {@code expire_at}
 * 与存档器的计算口径一致，同时不需要为每个用例造一整张日历。
 */
public final class TradingDays {

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private TradingDays() {
    }

    /**
     * 从 {@code tradeDate} 起顺延 {@code tradingDays} 个交易日（仅跳周末）。
     *
     * @param tradeDate  基准交易日 {@code yyyyMMdd}
     * @param tradingDays 顺延交易日数（≥1）
     * @return 目标日期 {@code yyyyMMdd}
     */
    public static String after(String tradeDate, int tradingDays) {
        LocalDate cursor = LocalDate.parse(tradeDate, COMPACT);
        int remaining = tradingDays;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY
                    && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return cursor.format(COMPACT);
    }

    /** {@code yyyyMMdd} → {@code yyyy-MM-dd}（判断 payload 的到期日口径）。 */
    public static String toIsoDate(String compactDate) {
        return LocalDate.parse(compactDate, COMPACT).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
