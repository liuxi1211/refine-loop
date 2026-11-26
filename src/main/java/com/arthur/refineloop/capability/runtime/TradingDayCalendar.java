package com.arthur.refineloop.capability.runtime;

/**
 * 交易日历端口：只回答「N 个交易日之后是哪一天」。
 *
 * <p>为什么是一个端口而不是直接注入 Mapper：降级判断的有效期口径是<b>交易日</b>
 * （能力详案 7.3），而交易日历属数据层；校验链/模板不该为算日期去认识任何表。
 * 需要替换数据源（或单测里给定固定日历）时只换实现。
 */
@FunctionalInterface
public interface TradingDayCalendar {

    /**
     * 从 {@code tradeDate} 起顺延 {@code tradingDays} 个交易日。
     *
     * @param tradeDate   基准交易日 {@code yyyyMMdd}
     * @param tradingDays 顺延的交易日数（≥1）
     * @return 目标交易日 {@code yyyyMMdd}
     * @throws com.arthur.refineloop.common.ApiException 400 参数非法 / 1003 交易日历不足
     */
    String plusTradingDays(String tradeDate, int tradingDays);
}
