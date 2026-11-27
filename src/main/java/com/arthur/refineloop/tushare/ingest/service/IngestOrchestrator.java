package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.TableGroup;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobResult;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.dto.LayerProgress;
import com.arthur.refineloop.tushare.ingest.dto.TableJobProgress;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobFailedEvent;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日接入编排：按依赖分层调度（维表层 → 行情层 → 财务与事件层）。
 *
 * <p>纪律：
 * <ul>
 *   <li>单实例编排互斥：同一时间仅一个编排 RUNNING，重复触发拒绝；</li>
 *   <li>每表作业写 ingest_log（同 run_id 分组），失败重试由 {@link IngestJobService} 内部完成，
 *       最终失败置 FAILED 并发 {@link IngestJobFailedEvent}（本批次仅日志，T17 接告警）；</li>
 *   <li>每日增量开关关闭的表记 SKIPPED 跳过（留痕）；hk_hold 注册表默认停用；</li>
 *   <li>进度快照供 GET /api/ops/ingest/orchestrations/current 轮询，进程重启后可从 ingest_log 重建。</li>
 * </ul>
 */
@Component
public class IngestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);
    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static final String LAYER_DIMENSION = "维表层";
    public static final String LAYER_QUOTE = "行情层";
    public static final String LAYER_FINANCE_EVENT = "财务与事件层";

    private final IngestJobService jobService;
    private final IngestLogService ingestLogService;
    private final SysConfigService sysConfigService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger runSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ingest-orchestrator");
        t.setDaemon(true);
        return t;
    });
    private volatile RunProgress currentProgress;

    public IngestOrchestrator(IngestJobService jobService, IngestLogService ingestLogService,
                              SysConfigService sysConfigService, ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.jobService = jobService;
        this.ingestLogService = ingestLogService;
        this.sysConfigService = sysConfigService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** 是否已有编排在运行（互斥判断）。 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 异步触发每日增量编排。
     *
     * @return runId；已有编排运行中返回 empty（调用方转 409）
     */
    public Optional<String> triggerDailyAsync() {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        String runId = newRunId("R");
        RunProgress progress = buildRunProgress(runId, "DAILY");
        currentProgress = progress;
        executor.execute(() -> {
            try {
                runLayers(progress);
            } catch (Throwable t) {
                log.error("每日编排异常终止 runId={}", runId, t);
                progress.finish("FAILED", LocalDateTime.now(clock));
            } finally {
                running.set(false);
            }
        });
        return Optional.of(runId);
    }

    /** 同步执行每日编排（CLI 入口用，阻塞到结束）。 */
    public OrchestrationProgress runDailyBlocking() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有编排正在运行，拒绝重复触发");
        }
        String runId = newRunId("R");
        RunProgress progress = buildRunProgress(runId, "CLI");
        currentProgress = progress;
        try {
            runLayers(progress);
        } finally {
            running.set(false);
        }
        return progress.snapshot(false);
    }

    /** 当前/最近一次编排进度（无记录时返回全 PENDING 的空编排）。 */
    public OrchestrationProgress currentProgress() {
        RunProgress progress = currentProgress;
        if (progress != null) {
            return progress.snapshot(running.get());
        }
        try {
            Optional<RunProgress> restored = restoreFromDb();
            if (restored.isPresent()) {
                return restored.get().snapshot(false);
            }
        } catch (Exception e) {
            log.debug("从 ingest_log 重建编排进度失败（可能无数据库）: {}", e.getMessage());
        }
        return idleProgress();
    }

    private void runLayers(RunProgress progress) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        progress.start(startedAt);
        log.info("每日编排开始 runId={} 共 {} 表", progress.runId, progress.tables.size());
        for (LayerDef layer : layers()) {
            for (TableDef def : layer.defs) {
                if (!sysConfigService.dailyEnabled(def.tableName(), def.dailyEnabledByDefault())) {
                    progress.update(def.tableName(), p -> {
                        p.status = "SKIPPED";
                        p.finishedAt = LocalDateTime.now(clock);
                        p.errorMsg = "每日增量已关闭/停用";
                    });
                    try {
                        ingestLogService.createSkipped(progress.runId, def.tableName(), IngestJobService.JOB_INCR,
                                "-", "每日增量已关闭/停用（注册表默认或 sys_config 覆盖）");
                    } catch (Exception e) {
                        log.warn("记录 SKIPPED 失败 table={}: {}", def.tableName(), e.getMessage());
                    }
                    continue;
                }
                progress.update(def.tableName(), p -> {
                    p.status = "RUNNING";
                    p.startedAt = LocalDateTime.now(clock);
                });
                IngestJobResult result = jobService.runJob(def, IngestJobService.JOB_INCR, null, null,
                        progress.runId);
                progress.update(def.tableName(), p -> {
                    p.status = result.status();
                    p.rowsWritten = result.rowsWritten();
                    p.durationMs = result.durationMs();
                    p.finishedAt = LocalDateTime.now(clock);
                    p.errorMsg = result.errorMessage();
                });
                if ("FAILED".equals(result.status())) {
                    eventPublisher.publishEvent(new IngestJobFailedEvent(progress.runId, def.tableName(),
                            IngestJobService.JOB_INCR, result.errorMessage(), LocalDateTime.now(clock)));
                }
            }
        }
        boolean anyFailed = progress.tables.stream().anyMatch(t -> "FAILED".equals(t.status));
        progress.finish(anyFailed ? "FAILED" : "SUCCESS", LocalDateTime.now(clock));
        log.info("每日编排结束 runId={} status={}", progress.runId, progress.status);
    }

    private List<LayerDef> layers() {
        List<TableDef> financeEvent = new ArrayList<>();
        financeEvent.addAll(TushareTableRegistry.byGroup(TableGroup.FINANCE));
        financeEvent.addAll(TushareTableRegistry.byGroup(TableGroup.EVENT));
        financeEvent.addAll(TushareTableRegistry.byGroup(TableGroup.INDEX_MARKET));
        return List.of(
                new LayerDef(LAYER_DIMENSION, TushareTableRegistry.byGroup(TableGroup.DIMENSION)),
                new LayerDef(LAYER_QUOTE, TushareTableRegistry.byGroup(TableGroup.QUOTE)),
                new LayerDef(LAYER_FINANCE_EVENT, financeEvent));
    }

    private record LayerDef(String layer, List<TableDef> defs) {
    }

    private RunProgress buildRunProgress(String runId, String triggerType) {
        List<TableProgress> tables = new ArrayList<>();
        for (LayerDef layer : layers()) {
            for (TableDef def : layer.defs) {
                tables.add(new TableProgress(def.tableName(), layer.layer));
            }
        }
        return new RunProgress(runId, triggerType, tables);
    }

    private Optional<RunProgress> restoreFromDb() {
        Optional<String> latestRunId = ingestLogService.latestRunId();
        if (latestRunId.isEmpty()) {
            return Optional.empty();
        }
        String runId = latestRunId.get();
        List<IngestJobView> jobs = ingestLogService.byRunId(runId);
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        RunProgress progress = buildRunProgress(runId, "DAILY");
        for (IngestJobView job : jobs) {
            progress.update(job.tableName(), p -> {
                p.status = job.status();
                p.rowsWritten = job.rowsWritten();
                p.durationMs = job.durationMs();
                p.startedAt = job.startTime();
                p.finishedAt = job.endTime();
                p.errorMsg = job.errorMsg();
            });
        }
        LocalDateTime startedAt = jobs.stream().map(IngestJobView::createdAt).filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime finishedAt = jobs.stream().map(IngestJobView::endTime).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        boolean anyFailed = jobs.stream().anyMatch(j -> "FAILED".equals(j.status()));
        progress.start(startedAt);
        boolean incomplete = progress.tables.stream()
                .anyMatch(table -> "PENDING".equals(table.status) || "RUNNING".equals(table.status));
        // 缺失表或非终态行都说明上次编排未完整结束，不能把「有日志」当成「全部成功」
        progress.finish(anyFailed || incomplete ? "FAILED" : "SUCCESS", finishedAt);
        return Optional.of(progress);
    }

    private OrchestrationProgress idleProgress() {
        RunProgress progress = buildRunProgress("-", "NONE");
        progress.finish("IDLE", null);
        return progress.snapshot(false);
    }

    private String newRunId(String prefix) {
        String suffix = Integer.toHexString(runSeq.incrementAndGet() & 0xFFFF);
        return prefix + LocalDateTime.now(clock).format(RUN_ID_FMT) + "-" + suffix;
    }

    /** 内部可变进度（单写线程 + 同步读快照）。 */
    private static final class RunProgress {
        final String runId;
        final String triggerType;
        final List<TableProgress> tables;
        String status = "PENDING";
        LocalDateTime startedAt;
        LocalDateTime finishedAt;

        RunProgress(String runId, String triggerType, List<TableProgress> tables) {
            this.runId = runId;
            this.triggerType = triggerType;
            this.tables = tables;
        }

        synchronized void start(LocalDateTime time) {
            this.status = "RUNNING";
            this.startedAt = time;
            this.finishedAt = null;
        }

        synchronized void finish(String status, LocalDateTime time) {
            this.status = status;
            this.finishedAt = time;
        }

        synchronized void update(String table, java.util.function.Consumer<TableProgress> mutator) {
            for (TableProgress p : tables) {
                if (p.table.equals(table)) {
                    mutator.accept(p);
                    return;
                }
            }
        }

        synchronized OrchestrationProgress snapshot(boolean runningNow) {
            List<LayerProgress> layers = new ArrayList<>();
            int total = tables.size();
            int done = 0;
            int failed = 0;
            int run = 0;
            int pending = 0;
            List<String> layerOrder = List.of(LAYER_DIMENSION, LAYER_QUOTE, LAYER_FINANCE_EVENT);
            for (String layerName : layerOrder) {
                List<TableJobProgress> layerTables = new ArrayList<>();
                int lDone = 0;
                int lFailed = 0;
                int lRun = 0;
                int lPending = 0;
                for (TableProgress p : tables) {
                    if (!p.layer.equals(layerName)) {
                        continue;
                    }
                    layerTables.add(new TableJobProgress(p.table, p.status, p.rowsWritten, p.durationMs,
                            p.startedAt, p.finishedAt, p.errorMsg));
                    switch (p.status) {
                        case "SUCCESS", "SKIPPED" -> lDone++;
                        case "FAILED" -> lFailed++;
                        case "RUNNING" -> lRun++;
                        default -> lPending++;
                    }
                }
                done += lDone;
                failed += lFailed;
                run += lRun;
                pending += lPending;
                int lTotal = layerTables.size();
                int lPercent = lTotal == 0 ? 100 : (int) Math.round((lDone + lFailed) * 100.0 / lTotal);
                layers.add(new LayerProgress(layerName, layerTables, lTotal, lDone, lFailed, lRun, lPending,
                        lPercent));
            }
            int percent = total == 0 ? 100 : (int) Math.round((done + failed) * 100.0 / total);
            return new OrchestrationProgress(runningNow, runId, status, startedAt, finishedAt, triggerType,
                    layers, total, done, failed, run, pending, percent);
        }
    }

    /** 单表内部进度。 */
    private static final class TableProgress {
        final String table;
        final String layer;
        String status = "PENDING";
        long rowsWritten;
        Long durationMs;
        LocalDateTime startedAt;
        LocalDateTime finishedAt;
        String errorMsg;

        TableProgress(String table, String layer) {
            this.table = table;
            this.layer = layer;
        }
    }
}
