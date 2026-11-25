package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.RateLimitCounterEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * rate_limit_counter 数据访问：按 (api_name, minute_slot) 原子累加，
 * 用 SQLite {@code ON CONFLICT ... DO UPDATE SET call_count = call_count + 1} 一条语句完成，
 * 不需要「先查后写」的竞态路径。
 */
@Mapper
public interface RateLimitCounterMapper extends BaseMapper<RateLimitCounterEntity> {

    @Insert("""
            INSERT INTO rate_limit_counter (api_name, minute_slot, call_count)
            VALUES (#{apiName}, #{minuteSlot}, 1)
            ON CONFLICT (api_name, minute_slot) DO UPDATE SET call_count = call_count + 1
            """)
    int increment(@Param("apiName") String apiName, @Param("minuteSlot") String minuteSlot);
}
