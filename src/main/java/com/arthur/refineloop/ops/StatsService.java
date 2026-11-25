package com.arthur.refineloop.ops;

import com.arthur.refineloop.data.mapper.OpsStatsMapper;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.service.IngestFacade;
import com.arthur.refineloop.tushare.quality.QualityCheckLogStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/ops/stats/today 的聚合服务（数据总览 4 张统计卡的数据源）。
 *
 * <p>本类属运维读模型域（{@code com.arthur.refineloop.ops}）：只依赖 data/tushare 域，向 web 适配层
 * 输出 {@link OpsViews}，不反向依赖 web 包。
 *
 * <p>数据来源（契约 §9.4）：{@code ingest_log}（created_at ∈ 今日）、
 * {@code rate_limit_counter}（今日 minute_slot 的 call_count 求和）、
 * {@code quality_check_log}（最近一次检查）、{@link TableOpsService} 的表健康度汇总、
 * {@link IngestFacade#currentOrchestration()} 的当前/最近编排。
 */
@Service
public class StatsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PENDING = "PENDING";

    private final OpsStatsMapper opsStatsMapper;
    private final Clock clock;
    private final TableOpsService tableOpsService;
    private final IngestFacade ingestFacade;
    private final QualityCheckLogStore qualityCheckLogStore;

    public StatsService(OpsStatsMapper opsStatsMapper, Clock clock, TableOpsService tableOpsService,
                        IngestFacade ingestFacade, QualityCheckLogStore qualityCheckLogStore) {
        this.opsStatsMapper = opsStatsMapper;
        this.clock = clock;
        this.tableOpsService = tableOpsService;
        this.ingestFacade = ingestFacade;
        this.qualityCheckLogStore = qualityCheckLogStore;
    }

    /** 今日统计（时间口径 Asia/Shanghai）。 */
    public OpsViews.StatsToday today() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();

        OpsViews.TodayJobs jobs = jobs(dayStart, dayEnd);
        long tushareCalls = tushareCallsToday(today);
        OpsViews.TablesSummary summary = tableOpsService.overview().summary();
        OpsViews.TodayTables tables = new OpsViews.TodayTables(
                summary.total(), summary.healthy(), summary.lag(), summary.error(), summary.idle());
        OpsViews.TodayQuality quality = qualityCheckLogStore.latest()
                .map(report -> new OpsViews.TodayQuality(report.result(), report.checkTime()))
                .orElse(null);
        return new OpsViews.StatsToday(today.format(DATE_FORMAT), jobs, tushareCalls, tables, quality,
                lastOrchestration());
    }

    private OpsViews.TodayJobs jobs(LocalDateTime dayStart, LocalDateTime dayEnd) {
        Map<String, Long> counts = new HashMap<>();
        List<Map<String, Object>> rows = opsStatsMapper.countJobsByStatus(dayStart, dayEnd);
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            Object count = row.get("c");
            if (status != null && count instanceof Number number) {
                counts.put(String.valueOf(status), number.longValue());
            }
        }
        long success = counts.getOrDefault(STATUS_SUCCESS, 0L);
        long failed = counts.getOrDefault(STATUS_FAILED, 0L);
        long running = counts.getOrDefault(STATUS_RUNNING, 0L);
        long pending = counts.getOrDefault(STATUS_PENDING, 0L);
        // total 只统计可执行作业（不含 SKIPPED）：与 success+failed+running+pending 对齐
        long total = success + failed + running + pending;
        return new OpsViews.TodayJobs(total, success, failed, running, pending);
    }

    private long tushareCallsToday(LocalDate today) {
        String prefix = today.format(DATE_FORMAT);
        return opsStatsMapper.sumCallCountByMinutePrefix(prefix + "%");
    }

    private OpsViews.LastOrchestration lastOrchestration() {
        OrchestrationProgress progress = ingestFacade.currentOrchestration();
        if (progress == null || progress.runId() == null || "-".equals(progress.runId())
                || "IDLE".equals(progress.status())) {
            return null;
        }
        return new OpsViews.LastOrchestration(progress.runId(), progress.status(), progress.startedAt(),
                progress.finishedAt());
    }
}
