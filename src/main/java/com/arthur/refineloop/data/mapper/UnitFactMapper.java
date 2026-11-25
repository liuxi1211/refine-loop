package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.UnitFactEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * unit_fact 数据访问：幂等 upsert + 按单元/日期读取。
 *
 * <p>幂等键 {@code (unit_id, unit_version, ts_code, trade_date)}；重复执行只更新 payload/状态，
 * 保证 compute 单元可重跑。
 */
@Mapper
public interface UnitFactMapper extends BaseMapper<UnitFactEntity> {

    @Insert("INSERT INTO unit_fact (unit_id, unit_version, ts_code, trade_date, payload, data_status, created_at) "
            + "VALUES (#{unitId}, #{unitVersion}, #{tsCode}, #{tradeDate}, #{payload}, #{dataStatus}, #{createdAt}) "
            + "ON CONFLICT (unit_id, unit_version, ts_code, trade_date) DO UPDATE SET "
            + "payload = excluded.payload, data_status = excluded.data_status")
    int upsert(UnitFactEntity entity);

    @Select("SELECT * FROM unit_fact WHERE unit_id = #{unitId} AND unit_version = #{unitVersion} "
            + "AND trade_date = #{tradeDate} ORDER BY ts_code")
    List<UnitFactEntity> selectByUnitAndDate(@Param("unitId") String unitId,
                                             @Param("unitVersion") String unitVersion,
                                             @Param("tradeDate") String tradeDate);

    @Select("SELECT * FROM unit_fact WHERE trade_date = #{tradeDate} AND ts_code = #{tsCode} "
            + "ORDER BY unit_id")
    List<UnitFactEntity> selectByTradeDateAndTsCode(@Param("tradeDate") String tradeDate,
                                                    @Param("tsCode") String tsCode);
}
