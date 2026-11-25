package com.arthur.refineloop.ops;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运维读模型视图集合（契约 §9 的 JSON 结构逐字段对齐；web 层只做序列化，不再定义出参）。
 *
 * <p>全部以 {@code Result<T>} 包装返回；时间用 {@link LocalDateTime}（Jackson 输出 ISO 字符串），
 * 业务日期统一 {@code yyyyMMdd} 字符串。
 */
public final class OpsViews {

    private OpsViews() {
    }

    // ================================================================ tables 模块

    /** GET /api/ops/tables 的 summary（健康度汇总）。 */
    public record TablesSummary(int total, int healthy, int lag, int error, int idle, int dailyEnabled) {
    }

    /** GET /api/ops/tables 的单表元数据行。 */
    public record TableMeta(
            String table,
            String api,
            String chineseName,
            String group,
            String strategy,
            String strategyLabel,
            long rowCount,
            boolean rowCountApprox,
            String latestDate,
            String latestDateColumn,
            Integer lagDays,
            String lastJobStatus,
            LocalDateTime lastJobTime,
            String lastJobType,
            boolean dailyEnabled,
            String health,
            int rateLimit,
            String unitNote) {
    }

    /** GET /api/ops/tables 的数据体。 */
    public record TablesOverview(TablesSummary summary, List<TableMeta> tables) {
    }

    /** 表列元数据（SQLite: PRAGMA table_info('<table>')）。 */
    public record TableColumnInfo(String name, String type, String comment) {
    }

    /** 近 N 个业务日按 latestDateColumn 分组的落库行数点。 */
    public record DailyLoadPoint(String date, long rows) {
    }

    /** 最近一次全量回填作业摘要。 */
    public record LastFullBackfill(
            String jobType,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long rowsWritten) {
    }

    /** GET /api/ops/tables/{table} 的数据体。 */
    public record TableDetail(
            String table,
            String api,
            String chineseName,
            String group,
            String strategy,
            String strategyLabel,
            String pk,
            long rowCount,
            boolean rowCountApprox,
            String latestDate,
            String latestDateColumn,
            Integer lagDays,
            int rateLimit,
            String unitNote,
            boolean dailyEnabled,
            LastFullBackfill lastFullBackfill,
            List<DailyLoadPoint> recentDailyLoad,
            List<TableColumnInfo> columns) {
    }

    /** GET /api/ops/tables/{table}/preview 的数据体。 */
    public record PreviewResult(
            String table,
            String columnMode,
            List<String> columns,
            List<Map<String, Object>> rows,
            long total,
            int page,
            int size,
            boolean limited) {
    }

    /** POST /api/ops/tables/{table}/daily-toggle 响应体。 */
    public record DailyToggleResult(String table, boolean dailyEnabled) {
    }

    // ================================================================ quality 模块

    /** GET /api/ops/quality/history 的单行（含逐状态计数）。 */
    public record QualityHistoryItem(
            long checkId,
            LocalDateTime checkTime,
            String triggerType,
            String result,
            long durationMs,
            int passCount,
            int warnCount,
            int failCount) {
    }

    /** GET /api/ops/quality/history 的数据体（分页）。 */
    public record QualityHistoryPage(List<QualityHistoryItem> items, long total, int page, int size) {
    }

    // ================================================================ stats 模块

    /** 今日作业计数（不含 SKIPPED：跳过作业不计入可执行作业数）。 */
    public record TodayJobs(long total, long success, long failed, long running, long pending) {
    }

    /** 今日表健康度汇总（同 GET /api/ops/tables 的 summary，去掉 dailyEnabled）。 */
    public record TodayTables(int total, int healthy, int lag, int error, int idle) {
    }

    /** 质量门最近一次结果摘要。 */
    public record TodayQuality(String result, LocalDateTime checkTime) {
    }

    /** 最近一次编排摘要。 */
    public record LastOrchestration(
            String runId,
            String status,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    /** GET /api/ops/stats/today 的数据体。 */
    public record StatsToday(
            String date,
            TodayJobs jobs,
            long tushareCallsToday,
            TodayTables tables,
            TodayQuality quality,
            LastOrchestration lastOrchestration) {
    }
}
