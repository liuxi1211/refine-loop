package com.arthur.refineloop.data.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** GET /api/ops/stats/today 的聚合查询（ingest_log 状态分布 + rate_limit_counter 今日调用量）。 */
@Mapper
public interface OpsStatsMapper {

    /** 指定时间窗内各状态作业数：返回 [{status, c}]。 */
    @Select("SELECT status, COUNT(*) AS c FROM ingest_log "
            + "WHERE created_at >= #{from} AND created_at < #{to} GROUP BY status")
    List<Map<String, Object>> countJobsByStatus(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /** 指定前缀（yyyyMMdd）分钟的调用次数合计。 */
    @Select("SELECT COALESCE(SUM(call_count), 0) FROM rate_limit_counter WHERE minute_slot LIKE #{prefixLike}")
    long sumCallCountByMinutePrefix(@Param("prefixLike") String prefixLike);
}
