package com.arthur.refineloop.tushare.ingest.dto;

/**
 * 单个接入作业的执行结果（同步执行后返回；编排层据此更新进度）。
 *
 * @param status SUCCESS / FAILED
 */
public record IngestJobResult(
        long jobId,
        String table,
        String jobType,
        String status,
        long rowsFetched,
        long rowsWritten,
        long durationMs,
        String errorMessage) {
}
