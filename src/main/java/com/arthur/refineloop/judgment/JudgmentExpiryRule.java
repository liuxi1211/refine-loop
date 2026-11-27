package com.arthur.refineloop.judgment;

/**
 * 判断到期日计算端口：把「有效期 N 个交易日」翻译成确定的自然日期。
 *
 * <p>依赖方向：judgment 内部端口，实现只依赖 data；不依赖 capability，避免存档域耦合具体单元。
 * 不变量：返回 {@code yyyyMMdd}，必须严格晚于入参交易日；日历不足时抛错，禁止自然日近似。
 */
@FunctionalInterface
public interface JudgmentExpiryRule {

    /**
     * @param tradeDate 基准交易日 {@code yyyyMMdd}
     * @param tradingDays 有效期（交易日，≥1）
     * @return 第 N 个交易日 {@code yyyyMMdd}
     */
    String plusTradingDays(String tradeDate, int tradingDays);
}
