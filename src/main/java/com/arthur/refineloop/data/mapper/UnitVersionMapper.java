package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.UnitVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * unit_version 数据访问：启动期登记单元版本（重复启动只校正主版本 prompt_path，不改 params/状态/激活时间）。
 */
@Mapper
public interface UnitVersionMapper extends BaseMapper<UnitVersionEntity> {

    @Insert("INSERT INTO unit_version (unit_id, version, change_note, prompt_path, params, status, created_at, activated_at) "
            + "VALUES (#{unitId}, #{version}, #{changeNote}, #{promptPath}, #{params}, #{status}, #{createdAt}, #{activatedAt}) "
            + "ON CONFLICT (unit_id, version) DO UPDATE SET "
            + "prompt_path = excluded.prompt_path")
    int upsert(UnitVersionEntity entity);

    @Select("SELECT * FROM unit_version WHERE unit_id = #{unitId} AND version = #{version}")
    UnitVersionEntity selectByUnitAndVersion(@Param("unitId") String unitId,
                                             @Param("version") String version);
}
