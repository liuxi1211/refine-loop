package com.arthur.refineloop.tushare.ingest.dto;

import com.arthur.refineloop.tushare.ingest.service.IngestLogQueryService;
import java.time.LocalDateTime;

/**
 * ingest_log 一行的只读视图（运维 API 作业历史 / 单表作业时间线共用）。
 *
 * <p>由 {@link IngestLogQueryService} 产出，字段与 db/schema.sql 的 ingest_log 列一一对应。
 */
public record IngestJobView(
        long id,
        String runId,
        String tableName,
        String jobType,
        String rangeParams,
        String status,
        long rowsFetched,
        long rowsWritten,
        int retryCount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMs,
        String errorMsg,
        LocalDateTime createdAt) {
}
