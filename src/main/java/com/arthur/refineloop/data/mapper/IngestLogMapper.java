package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.IngestLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ingest_log 数据访问（MyBatis-Plus BaseMapper + 少量注解查询）。
 *
 * <p>常规增删改查走 {@code BaseMapper}/{@code LambdaQueryWrapper}；这里只补充「每表最新作业」等
 * 需要在 SQL 里做 LIMIT 1 排序的查询，避免业务层拉全表。
 */
@Mapper
public interface IngestLogMapper extends BaseMapper<IngestLogEntity> {

    @Select("SELECT * FROM ingest_log WHERE table_name = #{table} ORDER BY id DESC LIMIT 1")
    IngestLogEntity selectLatestByTable(@Param("table") String table);

    @Select("SELECT * FROM ingest_log WHERE table_name = #{table} AND created_at >= #{since} "
            + "ORDER BY id DESC LIMIT 1")
    IngestLogEntity selectLatestByTableSince(@Param("table") String table,
                                             @Param("since") LocalDateTime since);

    @Select("SELECT * FROM ingest_log WHERE table_name = #{table} AND range_params LIKE #{rangeLike} "
            + "ORDER BY id DESC LIMIT 1")
    IngestLogEntity selectLatestByTableRange(@Param("table") String table,
                                             @Param("rangeLike") String rangeLike);

    @Select("SELECT * FROM ingest_log WHERE run_id = #{runId} ORDER BY id ASC")
    List<IngestLogEntity> selectByRunId(@Param("runId") String runId);

    /** 启动恢复：找出上次进程遗留的非终态作业（本系统当前只创建 RUNNING，PENDING 为兼容预留）。 */
    @Select("SELECT * FROM ingest_log WHERE status IN ('RUNNING', 'PENDING') ORDER BY id ASC")
    List<IngestLogEntity> selectActiveJobs();

    @Select("SELECT run_id FROM ingest_log WHERE run_id LIKE 'R%' ORDER BY id DESC LIMIT 1")
    String selectLatestRunId();
}
