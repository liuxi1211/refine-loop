package com.arthur.refineloop.tushare.ingest.dto;

import java.util.List;

/** 编排分层进度（维表层 / 行情层 / 财务与事件层）。 */
public record LayerProgress(
        String layer,
        List<TableJobProgress> tables,
        int total,
        int done,
        int failed,
        int running,
        int pending,
        int percent) {
}
