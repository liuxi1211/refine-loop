package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;

/**
 * 基于本地 {@code trade_cal} 的交易日历实现。
 *
 * <p>依赖方向：{@code capability.runtime → data.mapper}（允许）；只读查询，不触发任何远端拉取。
 *
 * <p>为什么不缓存：一次流水线只在降级时调用少量次数，SQLite 本地查询成本可忽略；
 * 缓存反而会在当日历回填后读到旧窗口。
 *
 * <p>不变量：返回日期严格晚于入参日期；日历不足以覆盖时抛 1003 而不是猜一个自然日。
 */
@Slf4j
public class TradeCalTradingDayCalendar implements TradingDayCalendar {

    /** 单次查询的初始自然日窗口（覆盖连续假期仍有余量）。 */
    private static final int MIN_WINDOW_DAYS = 15;

    /** 窗口翻倍上限：超过仍不足说明交易日历本身缺失，不再盲目扩大查询。 */
    private static final int MAX_WINDOW_DAYS = 400;

    private final TushareLookupMapper lookupMapper;

    public TradeCalTradingDayCalendar(TushareLookupMapper lookupMapper) {
        this.lookupMapper = lookupMapper;
    }

    @Override
    public String plusTradingDays(String tradeDate, int tradingDays) {
        LocalDate base = DateTimes.parseCompactDate(tradeDate);
        Asserts.isTrue(tradingDays >= 1, "tradingDays 必须 ≥ 1（当前 " + tradingDays + "）");
        String start = DateTimes.formatCompact(base.plusDays(1));

        int window = Math.max(MIN_WINDOW_DAYS, tradingDays * 3);
        while (window <= MAX_WINDOW_DAYS) {
            String end = DateTimes.formatCompact(base.plusDays(window));
            List<String> dates = openDates(start, end);
            if (dates.size() >= tradingDays) {
                return dates.get(tradingDays - 1);
            }
            window *= 2;
        }
        log.warn("[capability] 交易日历不足 tradeDate={} need={} windowDays={}", tradeDate, tradingDays,
                MAX_WINDOW_DAYS);
        throw ApiException.dataNotReady("交易日历不足：自 " + tradeDate + " 起 " + MAX_WINDOW_DAYS
                + " 天内找不到第 " + tradingDays + " 个交易日（请先回填 trade_cal）");
    }

    /** 先取 SSE 日历；SSE 未回填时退回任意交易所（与质量门同一兜底口径）。 */
    private List<String> openDates(String start, String end) {
        List<String> dates = lookupMapper.selectSseOpenTradeDates(start, end);
        return dates == null || dates.isEmpty()
                ? lookupMapper.selectAnyOpenTradeDates(start, end)
                : dates;
    }
}
