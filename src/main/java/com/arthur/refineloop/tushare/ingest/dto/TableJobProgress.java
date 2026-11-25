package com.arthur.refineloop.tushare.ingest.dto;

import java.time.LocalDateTime;

/** 编排内单表作业进度。 */
public record TableJobProgress(
        String table,
        String status,
        long rowsWritten,
        Long durationMs,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMsg) {
}
