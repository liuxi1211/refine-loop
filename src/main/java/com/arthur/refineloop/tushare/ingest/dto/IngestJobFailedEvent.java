package com.arthur.refineloop.tushare.ingest.dto;

import java.time.LocalDateTime;

/** 接入作业失败事件（本批次仅日志监听；T17 接告警通道）。 */
public record IngestJobFailedEvent(String runId, String table, String jobType, String errorMessage,
                                   LocalDateTime failedAt) {
}
