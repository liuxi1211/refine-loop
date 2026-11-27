package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;

import java.time.LocalDate;
import java.util.List;

/**
 * 基于本地 trade_cal 的判断到期日计算器（唯一口径：交易日）。
 *
 * <p>依赖方向：judgment → data。只读本地日历，不触发远端取数。
 * 不变量：不允许「日历不足就按自然日近似」，否则冻结后的 horizon_days 将不可比；日历不足应阻断存档并提示补数。
 */
public class JudgmentExpiryCalculator implements JudgmentExpiryRule {

    private static final int MIN_WINDOW_DAYS = 15;
    private static final int MAX_WINDOW_DAYS = 400;

    private final TushareLookupMapper lookupMapper;

    public JudgmentExpiryCalculator(TushareLookupMapper lookupMapper) {
        this.lookupMapper = lookupMapper;
    }

    @Override
    public String plusTradingDays(String tradeDate, int tradingDays) {
        Asserts.isTrue(tradingDays >= 1, "有效期必须 ≥ 1 个交易日（当前 " + tradingDays + "）");
        LocalDate base = DateTimes.parseCompactDate(tradeDate);
        String start = DateTimes.formatCompact(base.plusDays(1));
        int window = Math.max(MIN_WINDOW_DAYS, tradingDays * 3);
        while (window <= MAX_WINDOW_DAYS) {
            List<String> dates = openDates(start, DateTimes.formatCompact(base.plusDays(window)));
            if (dates.size() >= tradingDays) {
                return dates.get(tradingDays - 1);
            }
            window *= 2;
        }
        throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                "交易日历不足：无法确定 " + tradeDate + " 后第 " + tradingDays + " 个交易日，请先补齐 trade_cal");
    }

    private List<String> openDates(String start, String end) {
        List<String> dates = lookupMapper.selectSseOpenTradeDates(start, end);
        return dates == null || dates.isEmpty()
                ? lookupMapper.selectAnyOpenTradeDates(start, end)
                : dates;
    }
}
