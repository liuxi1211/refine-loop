package com.arthur.refineloop.tushare.ingest.dto;

import java.util.List;

/**
 * 初始回填分晚计划（POST /api/ops/ingest/backfill-plan 的返回）。
 *
 * <p>本批次只生成计划不自动执行：真实回填需要 Tushare token 就绪后，
 * 由用户按批触发单表回填或使用 CLI 入口执行。
 */
public record BackfillPlan(String planId, String mode, List<String> tables, List<PlanBatch> batches,
                           String message) {

    /** 一晚执行的一批表。 */
    public record PlanBatch(int night, List<String> tables) {
    }
}
