package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.JudgmentUnitRefEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code judgment_unit_ref} 的数据访问：随判断存档一次写入，之后只读。
 *
 * <p>与 {@code judgment} 一样不继承 {@code BaseMapper}：引用行是判断资产的组成部分，
 * 存档后不允许改写（否则「哪版单元产出了这条判断」可以被事后篡改，归因分组就失去意义）。
 */
@Mapper
public interface JudgmentUnitRefMapper {

    @Insert("INSERT INTO judgment_unit_ref (judgment_id, unit_id, unit_version, fact_id, output_summary, created_at) "
            + "VALUES (#{judgmentId}, #{unitId}, #{unitVersion}, #{factId}, #{outputSummary}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JudgmentUnitRefEntity entity);

    @Select("SELECT id, judgment_id, unit_id, unit_version, fact_id, output_summary, created_at "
            + "FROM judgment_unit_ref WHERE judgment_id = #{judgmentId} ORDER BY unit_id")
    List<JudgmentUnitRefEntity> selectByJudgmentId(@Param("judgmentId") String judgmentId);
}
