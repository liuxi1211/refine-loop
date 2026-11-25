package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.UserPreferenceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code user_preference} 数据访问；写入用 SQLite 的
 * {@code ON CONFLICT(user_id, pref_key) DO UPDATE} 实现按用户维度的幂等 upsert。
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreferenceEntity> {

    @Insert("""
            INSERT INTO user_preference (user_id, pref_key, pref_value, updated_at)
            VALUES (#{userId}, #{prefKey}, #{prefValue}, #{updatedAt})
            ON CONFLICT (user_id, pref_key) DO UPDATE SET
              pref_value = excluded.pref_value,
              updated_at = excluded.updated_at
            """)
    int upsert(UserPreferenceEntity entity);
}
