package com.arthur.refineloop.data.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据接入所需的「维表查找」查询（固定表、固定列，类型化 Mapper）。
 *
 * <p>与 {@link TushareTableMapper}（41 张镜像表的动态 SQL）分工：这里只放业务语义明确的固定查询，
 * SQL 可在编译期审阅；动态表/列一律走 Provider。
 */
@Mapper
public interface TushareLookupMapper {

    /** SSE 交易日列表（trade_cal）。 */
    @Select("SELECT cal_date FROM trade_cal WHERE exchange = 'SSE' AND is_open = '1' "
            + "AND cal_date >= #{start} AND cal_date <= #{end} ORDER BY cal_date")
    List<String> selectSseOpenTradeDates(@Param("start") String start, @Param("end") String end);

    /** 任意交易所的交易日列表（SSE 缺失时兜底）。 */
    @Select("SELECT DISTINCT cal_date FROM trade_cal WHERE is_open = '1' "
            + "AND cal_date >= #{start} AND cal_date <= #{end} ORDER BY cal_date")
    List<String> selectAnyOpenTradeDates(@Param("start") String start, @Param("end") String end);

    /** 在市股票清单（stock_basic.list_status = 'L'），per-stock 策略的股票池。 */
    @Select("SELECT ts_code FROM stock_basic WHERE list_status = 'L' ORDER BY ts_code")
    List<String> selectListedStockCodes();

    /** 指数清单（index_basic），per-index 策略（index_daily / index_weight）的代码来源。 */
    @Select("SELECT ts_code FROM index_basic ORDER BY ts_code")
    List<String> selectIndexCodes();

    /** L1 行业代码清单（sw_industry），index_member_all 按 L1 循环拉取。 */
    @Select("SELECT index_code FROM sw_industry WHERE level = 'L1' ORDER BY index_code")
    List<String> selectL1IndustryCodes();

    /** 股票名称（stock_basic）；未知代码返回 null（自选股写入时做名称快照）。 */
    @Select("SELECT name FROM stock_basic WHERE ts_code = #{tsCode} LIMIT 1")
    String selectStockName(@Param("tsCode") String tsCode);
}
