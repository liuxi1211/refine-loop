package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.SwIndustryMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code sw_industry_member} 的只读查询（申万一级归属）。
 *
 * <p>「目标日归属」的判定口径：{@code in_date} 不晚于目标交易日，且 {@code out_date} 为空/空串/
 * 不早于目标交易日。未来才生效的成分绝不能提前纳入，否则历史回放会使用未来行业归属（look-ahead bias）。
 * 该表是 Tushare {@code index_member_all} 的全量替换表（无幂等唯一键），同一 {@code ts_code}
 * 可能同时存在多条进出记录，因此「取哪一条」必须显式按 {@code in_date} 最新一条决定。
 */
@Mapper
public interface SwIndustryMemberMapper {

    /**
     * 目标交易日仍在成分内的全部股票→申万一级映射（一次全量读，数据量约 5000 行）。
     *
     * @param tradeDate 交易日 {@code yyyyMMdd}
     * @return 成分行（同一 ts_code 可能多行，调用方按 in_date 取最新）
     */
    @Select("SELECT ts_code, l1_code, l1_name, in_date, out_date FROM sw_industry_member "
            + "WHERE l1_code IS NOT NULL AND l1_code <> '' "
            + "AND (in_date IS NULL OR in_date = '' OR in_date <= #{tradeDate}) "
            + "AND (out_date IS NULL OR out_date = '' OR out_date >= #{tradeDate}) "
            + "ORDER BY ts_code, COALESCE(in_date, '00000000')")
    List<SwIndustryMemberEntity> selectCurrentMembers(@Param("tradeDate") String tradeDate);

    /**
     * 单只标的在目标交易日的申万一级行业代码。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日 {@code yyyyMMdd}
     * @return L1 行业代码；无归属返回 null
     */
    @Select("SELECT l1_code FROM sw_industry_member "
            + "WHERE ts_code = #{tsCode} AND l1_code IS NOT NULL AND l1_code <> '' "
            + "AND (in_date IS NULL OR in_date = '' OR in_date <= #{tradeDate}) "
            + "AND (out_date IS NULL OR out_date = '' OR out_date >= #{tradeDate}) "
            + "ORDER BY COALESCE(in_date, '00000000') DESC LIMIT 1")
    String selectL1Code(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
