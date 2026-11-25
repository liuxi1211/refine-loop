package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.QualityCheckLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** quality_check_log 数据访问（BaseMapper 足够：最近一次/历史分页用 LambdaQueryWrapper 表达）。 */
@Mapper
public interface QualityCheckLogMapper extends BaseMapper<QualityCheckLogEntity> {
}
