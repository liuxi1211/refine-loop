package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.DailyBasicEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code daily_basic} 的只读查询（估值分位单元的计算输入）。
 *
 * <p>为什么单独一个 Mapper 而不是复用 {@code TushareTableMapper}：后者是「按表名动态拼列」的
 * 通用入口，语句无法在编译期审阅、也拿不到类型；本 Mapper 的两条查询是能力单元的固定取数路径，
 * 必须能对着 SQL 直接审阅索引命中情况（见 {@code db/schema.sql} 的 {@code idx_daily_basic_ts_code}）。
 *
 * <p>不变量：
 * <ul>
 *   <li>只读：本 Mapper 不提供任何写入方法（写入仍走 {@code TushareTableMapper} 的幂等 upsert）；</li>
 *   <li>窗口查询必须按 {@code ts_code} 传页内的代码集合（≤ {@code PAGE_SIZE}），
 *       禁止一次性把全市场三年序列读进内存（架构 §4.2.3 批量纪律）；</li>
 *   <li>结果按 {@code ts_code, trade_date} 升序，调用方据此顺序聚合，不再二次排序。</li>
 * </ul>
 */
@Mapper
public interface DailyBasicMapper {

    /**
     * 某一页标的在 {@code [startDate, endDate]} 窗口内的全部每日指标。
     *
     * @param tsCodes   页内股票代码（非空，建议 ≤50 个）
     * @param startDate 窗口起（含）{@code yyyyMMdd}
     * @param endDate   窗口止（含）{@code yyyyMMdd}
     * @return 按 (ts_code, trade_date) 升序的行
     */
    @Select("<script>"
            + "SELECT ts_code, trade_date, close, pe_ttm, pb, ps_ttm, dv_ttm, total_mv "
            + "FROM daily_basic "
            + "WHERE trade_date &gt;= #{startDate} AND trade_date &lt;= #{endDate} "
            + "AND ts_code IN "
            + "<foreach item='code' collection='tsCodes' open='(' separator=',' close=')'>#{code}</foreach> "
            + "ORDER BY ts_code, trade_date"
            + "</script>")
    List<DailyBasicEntity> selectWindow(@Param("tsCodes") List<String> tsCodes,
                                        @Param("startDate") String startDate,
                                        @Param("endDate") String endDate);

    /**
     * 某一交易日全市场的每日指标（只用于「申万一级同业中位数」的横截面聚合）。
     *
     * <p>一日约 5000~5500 行，属于可接受的单日横截面读数；历史序列绝不允许这样读。
     *
     * @param tradeDate 交易日 {@code yyyyMMdd}
     * @return 当日全部行（按 ts_code 升序）
     */
    @Select("SELECT ts_code, trade_date, pe_ttm FROM daily_basic "
            + "WHERE trade_date = #{tradeDate} ORDER BY ts_code")
    List<DailyBasicEntity> selectByTradeDate(@Param("tradeDate") String tradeDate);
}
