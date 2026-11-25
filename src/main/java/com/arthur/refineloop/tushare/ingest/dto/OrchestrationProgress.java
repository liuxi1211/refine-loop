package com.arthur.refineloop.tushare.ingest.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前/最近一次编排的分层进度（GET /api/ops/ingest/orchestrations/current）。
 *
 * <p>无运行中/历史编排时 {@code running=false}，各层表状态为 PENDING、进度 0。
 */
public record OrchestrationProgress(
        boolean running,
        String runId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String triggerType,
        List<LayerProgress> layers,
        int total,
        int done,
        int failed,
        int runningCount,
        int pending,
        int percent) {
}
