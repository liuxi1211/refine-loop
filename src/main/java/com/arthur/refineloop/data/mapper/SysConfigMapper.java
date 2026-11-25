package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.SysConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** sys_config 数据访问；写入用 SQLite 的 {@code ON CONFLICT(config_key) DO UPDATE} 实现幂等 upsert。 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfigEntity> {

    @Insert("""
            INSERT INTO sys_config (config_key, config_value, remark, updated_at)
            VALUES (#{configKey}, #{configValue}, #{remark}, #{updatedAt})
            ON CONFLICT (config_key) DO UPDATE SET
              config_value = excluded.config_value,
              remark = excluded.remark,
              updated_at = excluded.updated_at
            """)
    int upsert(SysConfigEntity entity);
}
