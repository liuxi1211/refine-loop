package com.arthur.refineloop.data.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据质量门（S0）使用的聚合查询（固定表、固定列）。
 *
 * <p>替代原先散在 {@code DataQualityGate} 里的字符串 SQL：查询集中在此，
 * 便于统一审阅与后续加索引/改口径。
 */
@Mapper
public interface QualityQueryMapper {

    /** 当日 daily 行数。 */
    @Select("SELECT COUNT(*) FROM daily WHERE trade_date = #{tradeDate}")
    long countDailyByDate(@Param("tradeDate") String tradeDate);

    /** 在市（L）股票总数。 */
    @Select("SELECT COUNT(*) FROM stock_basic WHERE list_status = 'L'")
    long countListedStocks();

    /** 当日复权因子行数（应与 daily 一致）。 */
    @Select("SELECT COUNT(*) FROM adj_factor WHERE trade_date = #{tradeDate}")
    long countAdjFactorByDate(@Param("tradeDate") String tradeDate);

    /** 当日 close ≤ 0 的脏数据行数。 */
    @Select("SELECT COUNT(*) FROM daily WHERE trade_date = #{tradeDate} AND close <= 0")
    long countNonPositiveCloseByDate(@Param("tradeDate") String tradeDate);

    /** daily 最新交易日。 */
    @Select("SELECT MAX(trade_date) FROM daily")
    String maxDailyTradeDate();

    /** fina_indicator 最新公告日。 */
    @Select("SELECT MAX(ann_date) FROM fina_indicator")
    String maxFinaIndicatorAnnDate();

    /** 交易日历：SSE 当日是否开盘（'1' 开市 / '0' 休市 / null 无记录）。 */
    @Select("SELECT is_open FROM trade_cal WHERE cal_date = #{tradeDate} AND exchange = 'SSE' LIMIT 1")
    String selectSseIsOpen(@Param("tradeDate") String tradeDate);

    /** 交易日历：任意交易所当日是否开盘（SSE 无记录时兜底）。 */
    @Select("SELECT is_open FROM trade_cal WHERE cal_date = #{tradeDate} LIMIT 1")
    String selectAnyIsOpen(@Param("tradeDate") String tradeDate);
}
