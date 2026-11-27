package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.TableGroup;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.tushare.ingest.dto.BackfillPlan;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.config.IngestProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link IngestFacade} 实现：运维 API 的接入动作入口。
 *
 * <p>单表增量/回填/重试均异步执行（单线程队列，避免同表并发写冲突）；每日编排由
 * {@link IngestOrchestrator} 负责且与单表动作互斥（编排运行中拒绝单表触发，防止同表并发写）。
 */
@Component
public class IngestFacadeImpl implements IngestFacade {

    private static final Logger log = LoggerFactory.getLogger(IngestFacadeImpl.class);
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 初始回填「标准方案」10 表（对应原型向导）。 */
    public static final List<String> STANDARD_BACKFILL_TABLES = List.of(
            "stock_basic", "trade_cal", "index_basic", "daily", "adj_factor", "daily_basic",
            "fina_indicator", "income", "balancesheet", "cashflow");
    private static final int BATCHES_PER_NIGHT = 8;

    private final IngestJobService jobService;
    private final IngestOrchestrator orchestrator;
    private final IngestLogService ingestLogService;
    private final SysConfigService sysConfigService;
    private final IngestProperties props;
    private final Clock clock;

    private final AtomicInteger seq = new AtomicInteger();
    /** 已入队但尚未结束的表：既用于重复提交 409，也用于与每日编排互斥。 */
    private final Set<String> inFlightTables = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ingest-single-table");
        t.setDaemon(true);
        return t;
    });

    public IngestFacadeImpl(IngestJobService jobService, IngestOrchestrator orchestrator,
                            IngestLogService ingestLogService, SysConfigService sysConfigService,
                            IngestProperties props, Clock clock) {
        this.jobService = jobService;
        this.orchestrator = orchestrator;
        this.ingestLogService = ingestLogService;
        this.sysConfigService = sysConfigService;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public IngestTriggerAck triggerDaily() {
        if (!inFlightTables.isEmpty()) {
            throw ApiException.conflict("单表作业仍在运行（" + String.join(",", inFlightTables)
                    + "），请等待结束后再触发每日编排，避免同表并发写");
        }
        Optional<String> runId = orchestrator.triggerDailyAsync();
        if (runId.isEmpty()) {
            return new IngestTriggerAck(false, null, "已有编排正在运行，请等待结束或查看当前进度");
        }
        return new IngestTriggerAck(true, runId.get(), "每日增量编排已接受，正在异步执行");
    }

    @Override
    public IngestTriggerAck triggerSingleTable(String table, String jobType, String start, String end) {
        TableDef def = requireTable(table);
        String normalizedType = normalizeJobType(jobType);
        if (orchestrator.isRunning()) {
            throw ApiException.conflict("每日编排运行中，单表作业暂不可触发（避免同表并发写），请稍后重试");
        }
        return submit(def, normalizedType, start, end, "已接受单表作业（" + def.tableName() + " / "
                + normalizedType + "），异步执行中");
    }

    @Override
    public IngestTriggerAck retryJob(long ingestLogId) {
        IngestJobView job = ingestLogService.findById(ingestLogId)
                .orElseThrow(() -> ApiException.notFound("作业不存在: " + ingestLogId));
        if (!"FAILED".equals(job.status())) {
            throw ApiException.badRequest("仅 FAILED 作业可重试，当前状态: " + job.status());
        }
        TableDef def = requireTable(job.tableName());
        String[] range = parseRange(job.rangeParams());
        if (orchestrator.isRunning()) {
            throw ApiException.conflict("每日编排运行中，重试暂不可触发，请稍后重试");
        }
        return submit(def, normalizeJobType(job.jobType()), range[0], range[1],
                "失败作业重试已接受（" + job.tableName() + "），异步执行中");
    }

    @Override
    public OrchestrationProgress currentOrchestration() {
        return orchestrator.currentProgress();
    }

    @Override
    public BackfillPlan createBackfillPlan(String mode, List<String> tables) {
        String normalizedMode = mode == null || mode.isBlank() ? "standard" : mode.trim().toLowerCase();
        List<String> selected;
        switch (normalizedMode) {
            case "standard" -> selected = new ArrayList<>(STANDARD_BACKFILL_TABLES);
            case "all" -> {
                selected = new ArrayList<>();
                for (TableDef def : TushareTableRegistry.ALL) {
                    selected.add(def.tableName());
                }
            }
            case "custom" -> {
                if (tables == null || tables.isEmpty()) {
                    throw ApiException.badRequest("custom 模式必须提供 tables 自选表清单");
                }
                selected = new ArrayList<>();
                for (String table : tables) {
                    selected.add(requireTable(table).tableName());
                }
            }
            default -> throw ApiException.badRequest("不支持的 mode: " + mode + "（可选 standard/all/custom）");
        }
        List<BackfillPlan.PlanBatch> batches = new ArrayList<>();
        int night = 1;
        for (int i = 0; i < selected.size(); i += BATCHES_PER_NIGHT) {
            List<String> batch = selected.subList(i, Math.min(selected.size(), i + BATCHES_PER_NIGHT));
            batches.add(new BackfillPlan.PlanBatch(night++, new ArrayList<>(batch)));
        }
        String planId = "BF-" + LocalDateTime.now(clock).format(ID_FMT) + "-"
                + Integer.toHexString(seq.incrementAndGet() & 0xFFFF);
        String message = "回填计划已生成（共 " + selected.size() + " 表 / " + batches.size()
                + " 晚，未自动执行）；可按晚触发单表回填，或使用 CLI: --ingest.full=<tables> 执行";
        try {
            sysConfigService.set("ingest.backfill.plan." + planId,
                    normalizedMode + "|" + String.join(",", selected),
                    "初始回填计划（" + batches.size() + " 晚，" + selected.size() + " 表）");
        } catch (Exception e) {
            log.warn("回填计划留痕写 sys_config 失败: {}", e.getMessage());
        }
        return new BackfillPlan(planId, normalizedMode, selected, batches, message);
    }

    private IngestTriggerAck submit(TableDef def, String jobType, String start, String end, String message) {
        String table = def.tableName();
        if (!inFlightTables.add(table)) {
            throw ApiException.conflict("该表已有单表作业在队列中或运行中：" + table + "，请勿重复提交");
        }
        String runId = "M" + LocalDateTime.now(clock).format(ID_FMT) + "-"
                + Integer.toHexString(seq.incrementAndGet() & 0xFFFF);
        try {
            executor.execute(() -> {
                try {
                    jobService.runJob(def, jobType, start, end, runId);
                } catch (Exception e) {
                    log.error("单表作业执行异常 table={} jobType={} runId={}", table, jobType, runId, e);
                } finally {
                    inFlightTables.remove(table);
                }
            });
        } catch (RuntimeException e) {
            inFlightTables.remove(table);
            throw e;
        }
        return new IngestTriggerAck(true, runId, message);
    }

    private TableDef requireTable(String table) {
        TableDef def = TushareTableRegistry.byTable(table);
        if (def == null) {
            throw ApiException.notFound("未知数据表: " + table);
        }
        return def;
    }

    private static String normalizeJobType(String jobType) {
        if (jobType == null || jobType.isBlank()) {
            return IngestJobService.JOB_INCR;
        }
        String normalized = jobType.trim().toUpperCase();
        if (!normalized.equals(IngestJobService.JOB_INCR) && !normalized.equals(IngestJobService.JOB_FULL)
                && !normalized.equals(IngestJobService.JOB_BACKFILL)) {
            throw ApiException.badRequest("不支持的 jobType: " + jobType + "（可选 INCR/FULL/BACKFILL）");
        }
        return normalized;
    }

    private static String[] parseRange(String rangeParams) {
        if (rangeParams == null || rangeParams.isBlank() || !rangeParams.contains("~")) {
            return new String[]{null, null};
        }
        String[] parts = rangeParams.split("~", 2);
        return new String[]{parts[0].isBlank() ? null : parts[0], parts[1].isBlank() ? null : parts[1]};
    }

    /** 供编排/CLI 复用的分层顺序与表数说明。 */
    public Map<String, List<String>> layerTableNames() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(IngestOrchestrator.LAYER_DIMENSION, names(TushareTableRegistry.byGroup(TableGroup.DIMENSION)));
        map.put(IngestOrchestrator.LAYER_QUOTE, names(TushareTableRegistry.byGroup(TableGroup.QUOTE)));
        List<TableDef> third = new ArrayList<>();
        third.addAll(TushareTableRegistry.byGroup(TableGroup.FINANCE));
        third.addAll(TushareTableRegistry.byGroup(TableGroup.EVENT));
        third.addAll(TushareTableRegistry.byGroup(TableGroup.INDEX_MARKET));
        map.put(IngestOrchestrator.LAYER_FINANCE_EVENT, names(third));
        return map;
    }

    private static List<String> names(List<TableDef> defs) {
        List<String> names = new ArrayList<>(defs.size());
        for (TableDef def : defs) {
            names.add(def.tableName());
        }
        return names;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
