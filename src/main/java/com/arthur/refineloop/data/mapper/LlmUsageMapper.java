package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.LlmUsageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * llm_usage 数据访问：T7 只做写入；按 run/时间聚合查询在 T17 成本控制实现。
 */
@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsageEntity> {
}
