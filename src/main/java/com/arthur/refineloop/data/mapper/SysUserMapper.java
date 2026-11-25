package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code sys_user} 数据访问。
 *
 * <p>只放表级 CRUD 与固定查询；用户名唯一性由 {@code uk_sys_user} 兜底，服务层做可读的 409 翻译。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUserEntity> {
}
