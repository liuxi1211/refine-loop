package com.arthur.refineloop.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.data.mapper.TushareTableMapper;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.tushare.ingest.service.IngestLogQueryService;
import com.arthur.refineloop.tushare.ingest.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Step6 tables 模块的只读服务：41 表总览 / 单表详情 / 数据预览 / 行数与最新日期统计 /
 * 每日增量开关切换。
 *
 * <p>本类属运维读模型域（{@code com.arthur.refineloop.ops}）：只依赖 data/tushare 域，向 web 适配层
 * 输出 {@link OpsViews}，不反向依赖 web 包。
 *
 * <p>约定（契约 §9.1）：
 * <ul>
 *   <li>表/列白名单全部来自 {@link TushareTableRegistry}，动态拼进 SQL 的标识符均来自注册表；</li>
 *   <li>行数统计用精确 {@code SELECT COUNT(*)}（SQLite 无 information_schema 行数统计），
 *       {@code rowCountApprox} 恒为 false；列元数据用 {@code PRAGMA table_info}；</li>
 *   <li>健康度：{@code err}=最近作业 FAILED；{@code idle}=每日增量关闭；{@code none}=无数据；
 *       {@code lag}=lagDays&gt;7；其余 {@code ok}。</li>
 * </ul>
 */
@Service
public class TableOpsService {

    private static final Logger log = LoggerFactory.getLogger(TableOpsService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 每日增量开关留痕文案的时间格式。 */
    private static final DateTimeFormatter REMARK_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 健康度：滞后阈值（天）。 */
    private static final int LAG_THRESHOLD_DAYS = 7;
    /** 关键列模式下除幂等键外额外展示的列数。 */
    private static final int KEY_MODE_EXTRA_COLUMNS = 8;
    /** 近 N 日落库行数点数量。 */
    private static final int RECENT_LOAD_DAYS = 10;

    private final TushareTableMapper tushareTableMapper;
    private final Clock clock;
    private final SysConfigService sysConfigService;
    private final IngestLogQueryService ingestLogQueryService;

    public TableOpsService(TushareTableMapper tushareTableMapper, Clock clock,
                           SysConfigService sysConfigService,
                           IngestLogQueryService ingestLogQueryService) {
        this.tushareTableMapper = tushareTableMapper;
        this.clock = clock;
        this.sysConfigService = sysConfigService;
        this.ingestLogQueryService = ingestLogQueryService;
    }

    // ================================================================ 总览

    /** GET /api/ops/tables：41 表元数据 + 健康度汇总。 */
    public OpsViews.TablesOverview overview() {
        int healthy = 0;
        int lag = 0;
        int error = 0;
        int idle = 0;
        int dailyEnabledCount = 0;
        List<OpsViews.TableMeta> tables = new ArrayList<>(TushareTableRegistry.ALL.size());
        for (TableDef def : TushareTableRegistry.ALL) {
            RowCount rowCount = rowCount(def.tableName());
            String latestDate = latestDate(def);
            Integer lagDays = lagDays(latestDate);
            Optional<IngestJobView> lastJob = ingestLogQueryService.latestByTable(def.tableName());
            boolean dailyEnabled = dailyEnabled(def);
            if (dailyEnabled) {
                dailyEnabledCount++;
            }
            String health = health(lastJob, dailyEnabled, rowCount.value(), lagDays);
            switch (health) {
                case "ok" -> healthy++;
                case "lag" -> lag++;
                case "err" -> error++;
                case "idle" -> idle++;
                default -> {
                    // none：无数据，不计入四类汇总（summary 仅覆盖 ok/lag/err/idle）
                }
            }
            IngestJobView job = lastJob.orElse(null);
            tables.add(new OpsViews.TableMeta(
                    def.tableName(),
                    def.apiName(),
                    def.chineseName(),
                    def.group().label(),
                    def.strategy().name(),
                    def.strategy().label(),
                    rowCount.value(),
                    rowCount.approximate(),
                    latestDate,
                    def.latestDateColumn(),
                    lagDays,
                    job == null ? null : job.status(),
                    job == null ? null : job.createdAt(),
                    job == null ? null : job.jobType(),
                    dailyEnabled,
                    health,
                    def.rateLimitPerMinute(),
                    def.unitNote()));
        }
        OpsViews.TablesSummary summary = new OpsViews.TablesSummary(
                TushareTableRegistry.ALL.size(), healthy, lag, error, idle, dailyEnabledCount);
        return new OpsViews.TablesOverview(summary, tables);
    }

    // ================================================================ 详情

    /** GET /api/ops/tables/{table}：抽屉详情（主键/行数/最新日期/上次全量回填/近10日落库/列定义）。 */
    public OpsViews.TableDetail detail(String table) {
        TableDef def = OpsParams.requireTable(table);
        RowCount rowCount = rowCount(def.tableName());
        String latestDate = latestDate(def);
        Integer lagDays = lagDays(latestDate);
        OpsViews.LastFullBackfill lastFullBackfill = ingestLogQueryService
                .lastFullBackfill(def.tableName())
                .map(job -> new OpsViews.LastFullBackfill(job.jobType(), job.status(), job.startTime(),
                        job.endTime(), job.rowsWritten()))
                .orElse(null);
        return new OpsViews.TableDetail(
                def.tableName(),
                def.apiName(),
                def.chineseName(),
                def.group().label(),
                def.strategy().name(),
                def.strategy().label(),
                pkOf(def),
                rowCount.value(),
                rowCount.approximate(),
                latestDate,
                def.latestDateColumn(),
                lagDays,
                def.rateLimitPerMinute(),
                def.unitNote(),
                dailyEnabled(def),
                lastFullBackfill,
                recentDailyLoad(def),
                columns(def));
    }

    // ================================================================ 预览

    /**
     * GET /api/ops/tables/{table}/preview：白名单列 + 代码/日期过滤 + LIMIT 保护的分页查询。
     *
     * @param spec 预览过滤与列模式（见 {@link PreviewSpec}）
     * @param page 分页参数（已由 {@code PageQuery} 校验，另受 10000 深度限制）
     */
    public OpsViews.PreviewResult preview(String table, PreviewSpec spec, PageQuery page) {
        TableDef def = OpsParams.requireTable(table);
        OpsParams.requirePreviewDepth(page);
        String mode = OpsParams.enumParam(spec.columnMode(), "key", "columnMode", "key", "all");
        String direction = OpsParams.enumParam(spec.order(), "desc", "order", "desc", "asc");
        String normalizedCode = OpsParams.optionalCode(spec.code());
        String normalizedStart = OpsParams.optionalDate(spec.start(), "start");
        String normalizedEnd = OpsParams.optionalDate(spec.end(), "end");
        OpsParams.requireRangeOrder(normalizedStart, normalizedEnd);

        String codeColumn = def.codeColumn();
        if (normalizedCode != null && (codeColumn == null || codeColumn.isBlank())) {
            throw ApiException.badRequest("该表无代码列，不支持 code 过滤: " + def.tableName());
        }
        String dateColumn = def.latestDateColumn();
        if ((normalizedStart != null || normalizedEnd != null)
                && (dateColumn == null || dateColumn.isBlank())) {
            throw ApiException.badRequest("该表无业务日期列，不支持日期区间过滤: " + def.tableName());
        }

        List<String> selectedColumns = selectColumns(def, mode);
        String orderColumn = orderColumn(def);
        long total = tushareTableMapper.countFiltered(def.tableName(), codeColumn, dateColumn,
                normalizedCode, normalizedStart, normalizedEnd);
        List<Map<String, Object>> rawRows = tushareTableMapper.preview(def.tableName(),
                String.join(", ", selectedColumns), codeColumn, dateColumn,
                normalizedCode, normalizedStart, normalizedEnd, orderColumn + " " + direction,
                page.limit(), page.offset());
        List<Map<String, Object>> rows = new ArrayList<>(rawRows.size());
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : selectedColumns) {
                // MyBatis Map 结果按列名（与 schema 一致的小写）返回；按注册表列名取值即可
                row.put(column, raw.get(column));
            }
            rows.add(row);
        }
        boolean limited = total > (long) page.page() * page.size();
        return new OpsViews.PreviewResult(def.tableName(), mode, selectedColumns, rows, total, page.page(),
                page.size(), limited);
    }

    // ================================================================ 行数/日期统计

    private record RowCount(long value, boolean approximate) {
    }

    /**
     * 行数统计：SQLite 无 {@code information_schema} 且不维护行数统计表，统一用精确
     * {@code SELECT COUNT(*)}（{@code rowCountApprox=false}）。
     *
     * <p>说明：`sqlite_master` 不含行数；`dbstat` 虚拟表可估页数但默认未编译进来，
     * 且对本项目规模（百万级行情表）COUNT(*) 走主键/索引扫描足够快，运维台按需刷新即可。
     */
    private RowCount rowCount(String table) {
        return new RowCount(tushareTableMapper.countAll(table), false);
    }

    /** 最新业务日期（latestDateColumn 为空时返回 null，如 sw_industry）。 */
    private String latestDate(TableDef def) {
        String column = def.latestDateColumn();
        if (column == null || column.isBlank()) {
            return null;
        }
        return tushareTableMapper.maxValue(def.tableName(), column);
    }

    /** 最新业务日期距今天（Asia/Shanghai）的自然日差；无法计算返回 null。 */
    private Integer lagDays(String latestDate) {
        if (latestDate == null || !latestDate.matches("\\d{8}")) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(latestDate, DATE_FORMAT);
            long days = ChronoUnit.DAYS.between(date, LocalDate.now(clock.withZone(ZONE)));
            return (int) Math.max(0L, days);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 近 10 个业务日落库行数（按 latestDateColumn 分组 COUNT，返回按日期升序，便于前端画柱图）。 */
    private List<OpsViews.DailyLoadPoint> recentDailyLoad(TableDef def) {
        String column = def.latestDateColumn();
        if (column == null || column.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> grouped = tushareTableMapper.groupCount(def.tableName(), column,
                RECENT_LOAD_DAYS);
        List<OpsViews.DailyLoadPoint> points = new ArrayList<>(grouped.size());
        for (Map<String, Object> row : grouped) {
            Object date = row.get("d");
            Object count = row.get("c");
            points.add(new OpsViews.DailyLoadPoint(date == null ? null : String.valueOf(date),
                    count instanceof Number number ? number.longValue() : 0L));
        }
        Collections.reverse(points);
        return points;
    }

    /**
     * 列定义：SQLite 用 {@code PRAGMA table_info('<table>')} 取列名与声明类型
     * （SQLite 无列注释，{@code comment} 返回 null；表/列口径以注册表与 schema 注释为准）。
     *
     * <p>表名来自注册表白名单，不经过任何外部输入拼接，因此 PRAGMA 传入安全。
     */
    private List<OpsViews.TableColumnInfo> columns(TableDef def) {
        try {
            List<Map<String, Object>> info = tushareTableMapper.tableInfo(def.tableName());
            List<OpsViews.TableColumnInfo> columns = new ArrayList<>(info.size());
            for (Map<String, Object> row : info) {
                Object name = row.get("name");
                Object type = row.get("type");
                columns.add(new OpsViews.TableColumnInfo(name == null ? null : String.valueOf(name),
                        type == null ? null : String.valueOf(type), null));
            }
            return columns;
        } catch (DataAccessException e) {
            log.debug("PRAGMA table_info 不可用，回退注册表列名 table={}: {}", def.tableName(), e.getMessage());
            return def.columns().stream()
                    .map(column -> new OpsViews.TableColumnInfo(column, null, null))
                    .toList();
        }
    }

    // ================================================================ 开关/健康度

    /**
     * 每日增量开关切换：写 sys_config 留痕操作时间，再回读生效值。
     *
     * <p>入参 {@code enabled} 为空 → 400；表名不在注册表 → 404（均由 {@link OpsParams} 判定）。
     */
    public OpsViews.DailyToggleResult toggleDaily(String table, Boolean enabled) {
        TableDef def = OpsParams.requireTable(table);
        if (enabled == null) {
            throw ApiException.badRequest("enabled 不能为空（true/false）");
        }
        String remark = "运维台手动切换每日增量 " + LocalDateTime.now(clock).format(REMARK_TIME_FORMAT);
        sysConfigService.set(SysConfigService.DAILY_TOGGLE_PREFIX + def.tableName(),
                String.valueOf(enabled), remark);
        boolean effective = sysConfigService.dailyEnabled(def.tableName(), def.dailyEnabledByDefault());
        return new OpsViews.DailyToggleResult(def.tableName(), effective);
    }

    /** 每日增量开关 = 注册表默认值 + sys_config 覆盖（复用 {@link SysConfigService#dailyEnabled}）。 */
    private boolean dailyEnabled(TableDef def) {
        return sysConfigService.dailyEnabled(def.tableName(), def.dailyEnabledByDefault());
    }

    /** 健康度：err → idle → none → lag → ok（契约 §9.1 顺序）。 */
    private static String health(Optional<IngestJobView> lastJob, boolean dailyEnabled, long rowCount,
                                 Integer lagDays) {
        if (lastJob.isPresent() && "FAILED".equals(lastJob.get().status())) {
            return "err";
        }
        if (!dailyEnabled) {
            return "idle";
        }
        if (rowCount <= 0L) {
            return "none";
        }
        if (lagDays != null && lagDays > LAG_THRESHOLD_DAYS) {
            return "lag";
        }
        return "ok";
    }

    /** 主键展示：幂等键列；无幂等键（全量替换表）返回 null。 */
    private static String pkOf(TableDef def) {
        List<String> keys = def.idempotencyKeys();
        return keys == null || keys.isEmpty() ? null : "(" + String.join(", ", keys) + ")";
    }

    /** 关键列模式：幂等键 + 前 8 个非键列；无幂等键时取前 8 列。 */
    private static List<String> selectColumns(TableDef def, String mode) {
        List<String> allColumns = def.columns();
        if ("all".equals(mode)) {
            return allColumns;
        }
        List<String> keys = def.idempotencyKeys() == null ? List.of() : def.idempotencyKeys();
        List<String> selected = new ArrayList<>(keys.size() + KEY_MODE_EXTRA_COLUMNS);
        for (String key : keys) {
            if (allColumns.contains(key) && !selected.contains(key)) {
                selected.add(key);
            }
        }
        int limit = selected.size() + KEY_MODE_EXTRA_COLUMNS;
        for (String column : allColumns) {
            if (selected.size() >= limit) {
                break;
            }
            if (!selected.contains(column)) {
                selected.add(column);
            }
        }
        if (selected.isEmpty()) {
            selected = allColumns.subList(0, Math.min(KEY_MODE_EXTRA_COLUMNS, allColumns.size()));
        }
        return selected;
    }

    /** 排序列表：业务日期列优先，其次幂等键首列。 */
    private static String orderColumn(TableDef def) {
        String dateColumn = def.latestDateColumn();
        if (dateColumn != null && !dateColumn.isBlank()) {
            return dateColumn;
        }
        List<String> keys = def.idempotencyKeys();
        if (keys != null && !keys.isEmpty()) {
            return keys.get(0);
        }
        return def.columns().get(0);
    }
}
