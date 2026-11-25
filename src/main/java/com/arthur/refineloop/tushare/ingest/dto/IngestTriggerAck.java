package com.arthur.refineloop.tushare.ingest.dto;

/**
 * 触发类接口的统一回执（异步作业）。
 *
 * @param accepted 是否已接受（编排互斥时可能 false/409）
 * @param runId    本次触发的编排/作业分组 id（ingest_log.run_id）
 * @param message  面向用户的说明文案
 */
public record IngestTriggerAck(boolean accepted, String runId, String message) {
}
