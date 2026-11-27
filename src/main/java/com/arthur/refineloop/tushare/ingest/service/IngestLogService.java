package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.common.page.PageResults;
import com.arthur.refineloop.data.entity.IngestLogEntity;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ingest_log 写入 + 查询（T4），数据访问走 MyBatis-Plus Mapper。
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS / FAILED / SKIPPED。
 * 同一 run_id 分组同一次编排；每表每次拉取一行，失败重试只更新 retry_count 而不是新增行。
 *
 * <p>实体→视图转换集中在此（{@link IngestJobView} 是运维 API 的出参契约，与表结构解耦）。
 */
@Component
public class IngestLogService implements IngestLogQueryService {

    private final IngestLogMapper mapper;
    private final Clock clock;

    public IngestLogService(IngestLogMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 创建 RUNNING 作业行并返回 id。 */
    public long createRunning(String runId, String table, String jobType, String rangeParams) {
        IngestLogEntity entity = new IngestLogEntity();
        LocalDateTime now = LocalDateTime.now(clock);
        entity.setRunId(runId);
        entity.setTableName(table);
        entity.setJobType(jobType);
        entity.setRangeParams(rangeParams);
        entity.setStatus("RUNNING");
        entity.setRowsFetched(0L);
        entity.setRowsWritten(0L);
        entity.setRetryCount(0);
        entity.setStartTime(now);
        entity.setCreatedAt(now);
        mapper.insert(entity);
        return requireId(entity);
    }

    /** 编排跳过某表（如 hk_hold 停用）时记一行 SKIPPED。 */
    public long createSkipped(String runId, String table, String jobType, String rangeParams, String reason) {
        IngestLogEntity entity = new IngestLogEntity();
        LocalDateTime now = LocalDateTime.now(clock);
        entity.setRunId(runId);
        entity.setTableName(table);
        entity.setJobType(jobType);
        entity.setRangeParams(rangeParams);
        entity.setStatus("SKIPPED");
        entity.setRowsFetched(0L);
        entity.setRowsWritten(0L);
        entity.setRetryCount(0);
        entity.setStartTime(now);
        entity.setEndTime(now);
        entity.setDurationMs(0L);
        entity.setErrorMsg(abbreviate(reason));
        entity.setCreatedAt(now);
        mapper.insert(entity);
        return entity.getId() == null ? -1L : entity.getId();
    }

    public void updateRetry(long id, int retryCount) {
        IngestLogEntity patch = new IngestLogEntity();
        patch.setId(id);
        patch.setRetryCount(retryCount);
        mapper.updateById(patch);
    }

    public void markSuccess(long id, long rowsFetched, long rowsWritten, LocalDateTime startTime,
                            LocalDateTime endTime) {
        IngestLogEntity patch = new IngestLogEntity();
        patch.setId(id);
        patch.setStatus("SUCCESS");
        patch.setRowsFetched(rowsFetched);
        patch.setRowsWritten(rowsWritten);
        patch.setEndTime(endTime);
        patch.setDurationMs(durationMs(startTime, endTime));
        mapper.updateById(patch);
    }

    public void markFailed(long id, long rowsFetched, long rowsWritten, int retryCount, String errorMessage,
                           LocalDateTime startTime, LocalDateTime endTime) {
        IngestLogEntity patch = new IngestLogEntity();
        patch.setId(id);
        patch.setStatus("FAILED");
        patch.setRowsFetched(rowsFetched);
        patch.setRowsWritten(rowsWritten);
        patch.setRetryCount(retryCount);
        patch.setEndTime(endTime);
        patch.setDurationMs(durationMs(startTime, endTime));
        patch.setErrorMsg(abbreviate(errorMessage));
        mapper.updateById(patch);
    }

    /**
     * 应用启动时把上次进程遗留的 RUNNING/PENDING 作业标为 FAILED。
     *
     * <p>异步队列不持久化，进程退出后不可能自行续跑；保留 RUNNING 会让质量门永久失败且前端无法重试。
     * 这里只改状态/结束时间/错误摘要，保留已经拉取与写入的行数供人工判断部分完成范围。
     *
     * @return 恢复的作业行数
     */
    public int recoverInterruptedJobs() {
        List<IngestLogEntity> activeJobs = mapper.selectActiveJobs();
        for (IngestLogEntity job : activeJobs) {
            IngestLogEntity patch = new IngestLogEntity();
            patch.setId(job.getId());
            patch.setStatus("FAILED");
            patch.setEndTime(LocalDateTime.now(clock));
            patch.setErrorMsg(abbreviate("服务重启，作业中断；已写入部分数据，请核对后重试"));
            mapper.updateById(patch);
        }
        return activeJobs.size();
    }

    private static long requireId(IngestLogEntity entity) {
        if (entity.getId() == null) {
            throw new IllegalStateException("ingest_log 插入未返回主键");
        }
        return entity.getId();
    }

    private static Long durationMs(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return java.time.Duration.between(start, end).toMillis();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1024 ? text : text.substring(0, 1024);
    }

    // ================================================================ 查询

    @Override
    public Optional<IngestJobView> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(IngestLogService::toView);
    }

    @Override
    public PageResult<IngestJobView> page(String status, String table, PageQuery query) {
        LambdaQueryWrapper<IngestLogEntity> wrapper = Wrappers.<IngestLogEntity>lambdaQuery()
                .eq(status != null && !status.isBlank(), IngestLogEntity::getStatus, status)
                .eq(table != null && !table.isBlank(), IngestLogEntity::getTableName, table);
        long total = mapper.selectCount(wrapper);
        // 手工 LIMIT/OFFSET（不引入分页插件：本接口固定「按 id 倒序 + 页码」一种语义）
        wrapper.orderByDesc(IngestLogEntity::getId)
                .last("LIMIT " + query.limit() + " OFFSET " + query.offset());
        List<IngestJobView> items = new ArrayList<>();
        for (IngestLogEntity entity : mapper.selectList(wrapper)) {
            items.add(toView(entity));
        }
        return PageResults.of(items, total, query);
    }

    @Override
    public List<IngestJobView> recentByTable(String table, int limit) {
        List<IngestLogEntity> rows = mapper.selectList(Wrappers.<IngestLogEntity>lambdaQuery()
                .eq(IngestLogEntity::getTableName, table)
                .orderByDesc(IngestLogEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
        return rows.stream().map(IngestLogService::toView).toList();
    }

    @Override
    public Optional<IngestJobView> lastFullBackfill(String table) {
        List<IngestLogEntity> rows = mapper.selectList(Wrappers.<IngestLogEntity>lambdaQuery()
                .eq(IngestLogEntity::getTableName, table)
                .in(IngestLogEntity::getJobType, "FULL", "BACKFILL")
                .orderByDesc(IngestLogEntity::getId)
                .last("LIMIT 1"));
        return rows.stream().findFirst().map(IngestLogService::toView);
    }

    @Override
    public Optional<IngestJobView> latestByTable(String table) {
        return Optional.ofNullable(mapper.selectLatestByTable(table)).map(IngestLogService::toView);
    }

    /** 该 run 下的全部作业行（编排进度重建用）。 */
    public List<IngestJobView> byRunId(String runId) {
        return mapper.selectByRunId(runId).stream().map(IngestLogService::toView).toList();
    }

    /** 最近一个有作业记录的 run_id（重启后重建编排进度用）。 */
    public Optional<String> latestRunId() {
        return Optional.ofNullable(mapper.selectLatestRunId());
    }

    private static IngestJobView toView(IngestLogEntity entity) {
        return new IngestJobView(
                entity.getId() == null ? 0L : entity.getId(),
                entity.getRunId(),
                entity.getTableName(),
                entity.getJobType(),
                entity.getRangeParams(),
                entity.getStatus(),
                entity.getRowsFetched() == null ? 0L : entity.getRowsFetched(),
                entity.getRowsWritten() == null ? 0L : entity.getRowsWritten(),
                entity.getRetryCount() == null ? 0 : entity.getRetryCount(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getDurationMs(),
                entity.getErrorMsg(),
                entity.getCreatedAt());
    }
}
