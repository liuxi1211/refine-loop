package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.tushare.ingest.dto.BackfillPlan;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import java.util.List;

/**
 * 数据接入触发门面（运维 API 的动作入口）。
 *
 * <p>所有方法内部保证：同一时间仅一个编排 RUNNING（重复触发由实现抛 409 / 返回 accepted=false）；
 * 单表增量/回填走异步线程，接口立即返回 {@link IngestTriggerAck}。
 */
public interface IngestFacade {

    /** 一键每日增量（创建编排 run）。 */
    IngestTriggerAck triggerDaily();

    /**
     * 单表触发。
     *
     * @param table   表名（注册表白名单）
     * @param jobType INCR / FULL / BACKFILL
     * @param start   起始业务日期 yyyyMMdd，可空
     * @param end     结束业务日期 yyyyMMdd，可空
     */
    IngestTriggerAck triggerSingleTable(String table, String jobType, String start, String end);

    /** 失败作业重试（仅 FAILED 可重试；透传限流等待语义）。 */
    IngestTriggerAck retryJob(long ingestLogId);

    /** 当前/最近一次编排的分层进度。 */
    OrchestrationProgress currentOrchestration();

    /**
     * 初始回填向导：生成分晚计划（不自动执行）。
     *
     * @param mode   standard / all / custom
     * @param tables custom 模式下的自选表
     */
    BackfillPlan createBackfillPlan(String mode, List<String> tables);
}
