package com.arthur.refineloop.judgment.model;

/**
 * 存档请求：一次 {@code emit_structured_judgment} 的全部入参。
 *
 * <p>字段分三组（身份 / 方法 / 判断内容），用参数 record 打包避免方法签名超过 4 个参数
 * （规范 01 §11）；也避免「boolean 位置参数」这种调用方读不懂的签名。
 *
 * @param tsCode    标的代码（{@code 000001.SZ}）
 * @param tradeDate 交易日 {@code yyyyMMdd}
 * @param pipelineId 流水线 id（{@code P1} / {@code P1-MANUAL}），与 trade_date、ts_code 一起构成「同组」
 * @param runId 产出该判断的 pipeline_run.run_id；事实与报告按同一 run 追加
 * @param reportId  关联报告主键（{@code report.id}）；降级判断指向 DEGRADED 占位报告
 * @param reportRef 报告引用（如 {@code reports/20261122/000001.SZ.md}），可为 null
 * @param method    方法版本快照（含参与单元清单）
 * @param judgment  判断内容（判断块 + 降级标记 + 数据缺口）
 */
public record JudgmentArchiveRequest(String tsCode,
                                     String tradeDate,
                                     String pipelineId,
                                      String runId,
                                     long reportId,
                                     String reportRef,
                                     MethodSnapshot method,
                                     JudgmentInput judgment) {
}
