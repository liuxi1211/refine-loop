package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.UnitFactEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * unit_fact 数据访问：同 run 幂等追加 + 按 run 精确读取。
 *
 * <p>幂等键 {@code (run_id, unit_id, unit_version, ts_code, trade_date)}；冲突不更新，
 * 由 {@code FactStore} 比较既有载荷，内容不同即拒绝（防止同一 run 事实被静默改写）。
 */
@Mapper
public interface UnitFactMapper extends BaseMapper<UnitFactEntity> {

    String COLUMNS = "run_id, unit_id, unit_version, schema_version, ts_code, trade_date, "
            + "payload, data_status, notes, created_at";

    @Insert("INSERT INTO unit_fact (run_id, unit_id, unit_version, schema_version, ts_code, trade_date,"
            + " payload, data_status, notes, created_at) "
            + "VALUES (#{runId}, #{unitId}, #{unitVersion}, #{schemaVersion}, #{tsCode}, #{tradeDate},"
            + " #{payload}, #{dataStatus}, #{notes}, #{createdAt}) "
            + "ON CONFLICT (run_id, unit_id, unit_version, ts_code, trade_date) DO NOTHING")
    int insertIgnore(UnitFactEntity entity);

    @Select("SELECT " + COLUMNS + " FROM unit_fact WHERE run_id = #{runId} AND unit_id = #{unitId} "
            + "AND unit_version = #{unitVersion} AND ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    UnitFactEntity selectByKey(@Param("runId") String runId,
                               @Param("unitId") String unitId,
                               @Param("unitVersion") String unitVersion,
                               @Param("tsCode") String tsCode,
                               @Param("tradeDate") String tradeDate);

    @Select("SELECT " + COLUMNS + " FROM unit_fact WHERE run_id = #{runId} AND unit_id = #{unitId} "
            + "AND unit_version = #{unitVersion} AND trade_date = #{tradeDate} ORDER BY ts_code, id")
    List<UnitFactEntity> selectByUnitAndDate(@Param("runId") String runId,
                                              @Param("unitId") String unitId,
                                              @Param("unitVersion") String unitVersion,
                                              @Param("tradeDate") String tradeDate);

    @Select("SELECT " + COLUMNS + " FROM unit_fact WHERE run_id = #{runId} AND trade_date = #{tradeDate} "
            + "AND ts_code = #{tsCode} ORDER BY unit_id, unit_version, id")
    List<UnitFactEntity> selectByTradeDateAndTsCode(@Param("runId") String runId,
                                                     @Param("tradeDate") String tradeDate,
                                                     @Param("tsCode") String tsCode);
}
