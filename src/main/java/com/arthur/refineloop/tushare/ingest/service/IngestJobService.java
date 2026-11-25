package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.WriteMode;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobResult;
import com.arthur.refineloop.config.IngestProperties;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;
import com.arthur.refineloop.tushare.client.TushareClient;
import com.arthur.refineloop.tushare.client.TushareParamException;
import com.arthur.refineloop.tushare.client.TushareRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * T4 数据接入：五种更新策略的执行器（同步执行，编排层负责异步与分层）。
 *
 * <p>策略（手册第 4 章 +《计划》Step 4 实测修正）：
 * <ul>
 *   <li>ONE_SHOT：stock_basic（L/D/P 三态）、trade_cal、index_basic、sw_industry、sw_industry_member（按 L1 循环）；</li>
 *   <li>PER_STOCK：财务/股东类 per-stock（含 fina_mainbz 单股全历史、income/balance/cashflow 按报告期去重保留最新 ann_date）；</li>
 *   <li>PER_TRADE_DATE：行情/事件按交易日；</li>
 *   <li>PER_MONTH：按月窗口（含 suspend_d offset 超限降级 per-stock）；</li>
 *   <li>PER_PERIOD：季度末/周五/区间（disclosure_date、pledge_stat、report_rc、index_weight）。</li>
 * </ul>
 *
 * <p>纪律：HTTP 调用不包事务；每页/每批短事务由 {@link IngestTableWriter} 负责；500 行/批；
 * 失败按作业级指数退避重试 3 次后置 FAILED（参数/限流错误不重试）。
 */
@Component
public class IngestJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestJobService.class);

    public static final String JOB_INCR = "INCR";
    public static final String JOB_FULL = "FULL";
    public static final String JOB_BACKFILL = "BACKFILL";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> NO_DATE_PARAMS = List.of(
            "fina_mainbz", "pledge_detail", "top10_holders", "top10_floatholders", "stk_holdernumber");
    private static final List<String> CORE_INDEX_CODES = List.of(
            "000001.SH", "399001.SZ", "000300.SH", "000905.SH", "000852.SH", "399006.SZ", "000016.SH");
    private static final int MAX_MONTHS = 720;

    private final TushareClient client;
    private final IngestTableWriter writer;
    private final IngestLogService ingestLogService;
    private final TushareTableMapper tushareTableMapper;
    private final TushareLookupMapper tushareLookupMapper;
    private final Clock clock;
    private final IngestProperties props;

    public IngestJobService(TushareClient client, IngestTableWriter writer, IngestLogService ingestLogService,
                            TushareTableMapper tushareTableMapper, TushareLookupMapper tushareLookupMapper,
                            Clock clock, IngestProperties props) {
        this.client = client;
        this.writer = writer;
        this.ingestLogService = ingestLogService;
        this.tushareTableMapper = tushareTableMapper;
        this.tushareLookupMapper = tushareLookupMapper;
        this.clock = clock;
        this.props = props;
    }

    /** 同步执行一个表作业（含作业级重试与 ingest_log 记录）。 */
    public IngestJobResult runJob(TableDef def, String jobType, String start, String end, String runId) {
        String effectiveStart = resolveStart(def, jobType, start);
        String effectiveEnd = (end == null || end.isBlank()) ? today() : end;
        String range = effectiveStart + "~" + effectiveEnd;
        long jobId = ingestLogService.createRunning(runId, def.tableName(), jobType, range);
        LocalDateTime startTime = LocalDateTime.now(clock);
        AtomicLong fetched = new AtomicLong();
        AtomicLong written = new AtomicLong();
        int retryCount = 0;
        String error = null;

        while (true) {
            try {
                execute(def, jobType, effectiveStart, effectiveEnd, fetched, written);
                LocalDateTime endTime = LocalDateTime.now(clock);
                ingestLogService.markSuccess(jobId, fetched.get(), written.get(), startTime, endTime);
                long duration = Duration.between(startTime, endTime).toMillis();
                log.info("接入作业成功 table={} jobType={} fetched={} written={} duration={}ms",
                        def.tableName(), jobType, fetched.get(), written.get(), duration);
                return new IngestJobResult(jobId, def.tableName(), jobType, "SUCCESS",
                        fetched.get(), written.get(), duration, null);
            } catch (TushareParamException | TushareRateLimitException | IllegalArgumentException ex) {
                // 参数错误/限流/非法表列：不重试，直接失败
                error = ex.getMessage();
                break;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                error = "作业被中断: " + ex.getMessage();
                break;
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                retryCount++;
                if (retryCount > props.getJobRetryCount()) {
                    break;
                }
                ingestLogService.updateRetry(jobId, retryCount);
                long backoff = Math.max(0L, props.getJobRetryBackoffMillis()) << (retryCount - 1);
                log.warn("接入作业失败重试 table={} 第 {}/{} 次，等待 {}ms: {}", def.tableName(), retryCount,
                        props.getJobRetryCount(), backoff, error);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LocalDateTime endTime = LocalDateTime.now(clock);
        ingestLogService.markFailed(jobId, fetched.get(), written.get(), retryCount, error, startTime, endTime);
        long duration = Duration.between(startTime, endTime).toMillis();
        log.error("接入作业失败 table={} jobType={} retry={} error={}", def.tableName(), jobType, retryCount, error);
        return new IngestJobResult(jobId, def.tableName(), jobType, "FAILED", fetched.get(), written.get(),
                duration, error);
    }

    // =====================================================================
    // 策略分发
    // =====================================================================
    private void execute(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                         AtomicLong written) throws Exception {
        switch (def.strategy()) {
            case ONE_SHOT -> runOneShot(def, jobType, start, end, fetched, written);
            case PER_STOCK -> runPerStock(def, jobType, start, end, fetched, written);
            case PER_TRADE_DATE -> runPerTradeDate(def, jobType, start, end, fetched, written);
            case PER_MONTH -> runPerMonth(def, jobType, start, end, fetched, written);
            case PER_PERIOD -> runPerPeriod(def, jobType, start, end, fetched, written);
        }
    }

    // =====================================================================
    // 1) 一次性全量
    // =====================================================================
    private void runOneShot(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                            AtomicLong written) {
        switch (def.tableName()) {
            case "stock_basic" -> {
                List<Map<String, Object>> all = new ArrayList<>();
                for (String status : List.of("L", "D", "P")) {
                    all.addAll(fetchAllPages(def, Map.of("list_status", status), fetched));
                }
                written.addAndGet(writer.replaceAll(def, all));
            }
            case "trade_cal" -> {
                for (String exchange : List.of("SSE", "SZSE")) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("exchange", exchange);
                    params.put("start_date", start);
                    params.put("end_date", end);
                    forEachPage(def, params, fetched,
                            page -> written.addAndGet(writer.writeUpsert(def, page)));
                }
            }
            case "index_basic" -> {
                List<Map<String, Object>> all = fetchAllPages(def, Map.of(), fetched);
                written.addAndGet(writer.replaceAll(def, all));
            }
            case "sw_industry" -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("src", "SW2021");
                List<Map<String, Object>> all = fetchAllPages(def, params, fetched);
                written.addAndGet(writer.replaceAll(def, all));
            }
            case "sw_industry_member" -> {
                // 实测：src=SW2021 返回 0 行，必须按 L1 行业 index_code 循环拉取；全量替换策略
                List<String> l1Codes = queryL1IndexCodes();
                if (l1Codes.isEmpty()) {
                    log.warn("sw_industry_member：sw_industry 无 L1 行业代码，跳过（请先跑 sw_industry）");
                }
                List<Map<String, Object>> all = new ArrayList<>();
                for (String code : l1Codes) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("index_code", code);
                    all.addAll(fetchAllPages(def, params, fetched));
                }
                written.addAndGet(writer.replaceAll(def, all));
            }
            default -> throw new IllegalArgumentException("未实现的一次性全量接口: " + def.tableName());
        }
    }

    // =====================================================================
    // 2) per-stock
    // =====================================================================
    private void runPerStock(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                             AtomicLong written) throws Exception {
        List<String> codes = resolveStockCodes(def);
        if (codes.isEmpty()) {
            log.warn("per-stock 作业 {} 无股票清单可拉（stock_basic 为空？）", def.tableName());
            return;
        }
        int concurrency = Math.max(1, props.getPerStockConcurrency());
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        if (concurrency == 1) {
            // 单线程顺序执行（测试/低并发场景），便于确定性断言与调试
            for (String code : codes) {
                try {
                    processStock(def, code, start, end, fetched, written);
                } catch (Exception e) {
                    errors.add(e);
                    log.warn("per-stock 单股失败（记录后继续）table={} ts_code={}: {}", def.tableName(), code,
                            e.getMessage());
                }
            }
            afterPerStock(def, errors, fetched);
            return;
        }
        Semaphore semaphore = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(codes.size());
            for (String code : codes) {
                futures.add(executor.submit(() -> {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        processStock(def, code, start, end, fetched, written);
                    } catch (Exception e) {
                        errors.add(e);
                        log.warn("per-stock 单股失败（记录后继续）table={} ts_code={}: {}", def.tableName(), code,
                                e.getMessage());
                    } finally {
                        semaphore.release();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        afterPerStock(def, errors, fetched);
    }

    private void afterPerStock(TableDef def, ConcurrentLinkedQueue<Exception> errors, AtomicLong fetched)
            throws Exception {
        if (!errors.isEmpty()) {
            log.warn("per-stock 作业 {} 有 {} 只股票失败，首条错误: {}", def.tableName(), errors.size(),
                    errors.peek().getMessage());
            if (fetched.get() == 0) {
                throw errors.peek();
            }
        }
    }

    private void processStock(TableDef def, String code, String start, String end, AtomicLong fetched,
                              AtomicLong written) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ts_code", code);
        if (!NO_DATE_PARAMS.contains(def.tableName())) {
            params.put("start_date", start);
            params.put("end_date", end);
        }
        List<Map<String, Object>> rows = fetchAllPages(def, params, fetched);
        if (rows.isEmpty()) {
            return;
        }
        if ("keep_latest_ann_date".equals(def.options().get("dedup"))) {
            rows = dedupKeepLatestAnnDate(rows);
        }
        written.addAndGet(writeRows(def, rows));
    }

    // =====================================================================
    // 3) per-trade-date
    // =====================================================================
    private void runPerTradeDate(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                                 AtomicLong written) throws Exception {
        List<String> tradeDates = resolveTradeDates(start, end);
        if (tradeDates.isEmpty()) {
            log.warn("按交易日作业 {} 区间 {}~{} 无交易日", def.tableName(), start, end);
            return;
        }
        for (String tradeDate : tradeDates) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("trade_date", tradeDate);
            if (def.writeMode() == WriteMode.BLOCK_DELETE_INSERT) {
                List<Map<String, Object>> dayRows = fetchAllPages(def, params, fetched);
                if (!dayRows.isEmpty()) {
                    written.addAndGet(writeRows(def, dayRows));
                }
            } else {
                forEachPage(def, params, fetched, page -> written.addAndGet(writer.writeUpsert(def, page)));
            }
        }
    }

    // =====================================================================
    // 4) per-month 窗口
    // =====================================================================
    private void runPerMonth(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                             AtomicLong written) {
        List<YearMonth> months = monthsBetween(start, end);
        for (YearMonth month : months) {
            String monthStart = month.atDay(1).format(DATE_FMT);
            String monthEnd = month.atEndOfMonth().format(DATE_FMT);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("start_date", monthStart);
            params.put("end_date", monthEnd);
            Object freq = def.options().get("freq");
            if (freq != null) {
                params.put("freq", freq.toString());
            }
            if ("stock_suspend_d".equals(def.tableName())) {
                writeSuspendMonth(def, params, monthStart, monthEnd, fetched, written);
                continue;
            }
            if (def.writeMode() == WriteMode.BLOCK_DELETE_INSERT) {
                List<Map<String, Object>> monthRows = fetchAllPages(def, params, fetched);
                if (!monthRows.isEmpty()) {
                    written.addAndGet(writeRows(def, monthRows));
                }
            } else {
                forEachPage(def, params, fetched, page -> written.addAndGet(writer.writeUpsert(def, page)));
            }
        }
    }

    /** suspend_d：按月拆分；单月 offset 超 10 万时降级为 per-stock（ts_code + 月区间）。 */
    private void writeSuspendMonth(TableDef def, Map<String, Object> monthParams, String monthStart,
                                   String monthEnd, AtomicLong fetched, AtomicLong written) {
        PageFetchResult result = fetchPagesWithOffsetLimit(def, monthParams, fetched);
        if (!result.offsetExceeded()) {
            if (!result.rows().isEmpty()) {
                written.addAndGet(writer.writeUpsert(def, result.rows()));
            }
            return;
        }
        log.warn("suspend_d 单月 offset 超过 {}，降级为 per-stock: {}", props.getSuspendOffsetLimit(), monthStart);
        List<String> codes = resolveStockCodes(def);
        for (String code : codes) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("ts_code", code);
            params.put("start_date", monthStart);
            params.put("end_date", monthEnd);
            List<Map<String, Object>> rows = fetchAllPages(def, params, fetched);
            if (!rows.isEmpty()) {
                written.addAndGet(writer.writeUpsert(def, rows));
            }
        }
    }

    // =====================================================================
    // 5) per-period / 公告日
    // =====================================================================
    private void runPerPeriod(TableDef def, String jobType, String start, String end, AtomicLong fetched,
                              AtomicLong written) {
        switch (def.tableName()) {
            case "disclosure_date" -> {
                for (String quarterEnd : quarterEnds(start, end)) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("end_date", quarterEnd);
                    forEachPage(def, params, fetched, page -> written.addAndGet(writer.writeUpsert(def, page)));
                }
            }
            case "pledge_stat" -> {
                for (String friday : fridays(start, end)) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("end_date", friday);
                    List<Map<String, Object>> rows = fetchAllPages(def, params, fetched);
                    if (rows.isEmpty()) {
                        // 周五无数据时回补前一天（手册 6.4）
                        String prev = LocalDate.parse(friday, DATE_FMT).minusDays(1).format(DATE_FMT);
                        Map<String, Object> prevParams = new LinkedHashMap<>();
                        prevParams.put("end_date", prev);
                        rows = fetchAllPages(def, prevParams, fetched);
                    }
                    if (!rows.isEmpty()) {
                        written.addAndGet(writer.writeUpsert(def, rows));
                    }
                }
            }
            case "report_rc" -> {
                // 1 次/分钟 + 10 次/天：仅回补近 90 天且只拉 1 页
                String rangeStart = maxDate(start, LocalDate.now(clock).minusDays(90).format(DATE_FMT));
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("start_date", rangeStart);
                params.put("end_date", end);
                List<Map<String, Object>> rows = fetchAllPages(def, params, fetched);
                if (!rows.isEmpty()) {
                    written.addAndGet(writeRows(def, rows));
                }
            }
            case "index_weight" -> {
                List<String> indexCodes = resolveStockCodes(def);
                for (String indexCode : indexCodes) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("index_code", indexCode);
                    params.put("start_date", start);
                    params.put("end_date", end);
                    List<Map<String, Object>> rows = fetchAllPages(def, params, fetched);
                    if (!rows.isEmpty()) {
                        written.addAndGet(writer.writeUpsert(def, rows));
                    }
                }
            }
            default -> throw new IllegalArgumentException("未实现的按期/公告日接口: " + def.tableName());
        }
    }

    // =====================================================================
    // 写入辅助
    // =====================================================================
    /** 按注册表 writeMode 写入一批行，返回写入行数。FULL_REPLACE 由各策略显式整表替换。 */
    private int writeRows(TableDef def, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return switch (def.writeMode()) {
            case UPSERT -> writer.writeUpsert(def, rows);
            case BLOCK_DELETE_INSERT -> writeBlockDelete(def, rows);
            case FULL_REPLACE -> writer.replaceAll(def, rows);
        };
    }

    /** 整块先删后插：按幂等键把行分组，每组删除同键旧块后插入。 */
    private int writeBlockDelete(TableDef def, List<Map<String, Object>> rows) {
        List<String> keys = def.idempotencyKeys();
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> keyValues = new ArrayList<>(keys.size());
            boolean missing = false;
            for (String key : keys) {
                Object value = row.get(key);
                if (value == null || String.valueOf(value).isBlank()) {
                    missing = true;
                    break;
                }
                keyValues.add(value);
            }
            if (missing) {
                continue;
            }
            groups.computeIfAbsent(keyValues, k -> new ArrayList<>()).add(row);
        }
        int written = 0;
        for (List<Map<String, Object>> block : groups.values()) {
            written += writer.deleteAndInsertBlock(def, keys, block);
        }
        return written;
    }

    // =====================================================================
    // 分页与拉取
    // =====================================================================
    private List<Map<String, Object>> fetchAllPages(TableDef def, Map<String, Object> baseParams,
                                                    AtomicLong fetched) {
        List<Map<String, Object>> all = new ArrayList<>();
        forEachPage(def, baseParams, fetched, all::addAll);
        return all;
    }

    /** 标准 offset 分页：每页回调；超过 MAX_PAGES 告警停止（不空转）。 */
    private void forEachPage(TableDef def, Map<String, Object> baseParams, AtomicLong fetched,
                             Consumer<List<Map<String, Object>>> pageConsumer) {
        int offset = 0;
        int pageSize = Math.max(1, def.pageSize());
        int maxPages = Math.max(1, def.maxPages());
        for (int page = 0; page < maxPages; page++) {
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("offset", String.valueOf(offset));
            params.put("limit", String.valueOf(pageSize));
            List<Map<String, Object>> rows = client.call(def.apiName(), params, def.fields());
            fetched.addAndGet(rows.size());
            if (!rows.isEmpty()) {
                pageConsumer.accept(rows);
            }
            if (rows.size() < pageSize) {
                return;
            }
            offset += pageSize;
        }
        log.warn("分页达到 MAX_PAGES={} 保护，停止翻页 table={}（可能仍有数据未拉全）", maxPages, def.tableName());
    }

    private record PageFetchResult(List<Map<String, Object>> rows, boolean offsetExceeded) {
    }

    /** 带 offset 上限保护的分页（suspend_d 专用）。 */
    private PageFetchResult fetchPagesWithOffsetLimit(TableDef def, Map<String, Object> baseParams,
                                                      AtomicLong fetched) {
        List<Map<String, Object>> all = new ArrayList<>();
        int offset = 0;
        int pageSize = Math.max(1, def.pageSize());
        int maxPages = Math.max(1, def.maxPages());
        for (int page = 0; page < maxPages; page++) {
            if (offset >= props.getSuspendOffsetLimit()) {
                return new PageFetchResult(all, true);
            }
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("offset", String.valueOf(offset));
            params.put("limit", String.valueOf(pageSize));
            List<Map<String, Object>> rows = client.call(def.apiName(), params, def.fields());
            fetched.addAndGet(rows.size());
            all.addAll(rows);
            if (rows.size() < pageSize) {
                return new PageFetchResult(all, false);
            }
            offset += pageSize;
        }
        log.warn("suspend_d 分页达到 MAX_PAGES={} 保护 table={}", maxPages, def.tableName());
        return new PageFetchResult(all, false);
    }

    // =====================================================================
    // 日期/代码来源解析
    // =====================================================================
    private String resolveStart(TableDef def, String jobType, String explicitStart) {
        if (explicitStart != null && !explicitStart.isBlank()) {
            return explicitStart;
        }
        if (JOB_FULL.equals(jobType) || JOB_BACKFILL.equals(jobType)) {
            return props.getFullStartDate();
        }
        String latest = latestBusinessDate(def);
        if (latest != null && !latest.isBlank()) {
            return latest;
        }
        return LocalDate.now(clock).minusDays(Math.max(1, props.getIncrementalFallbackDays())).format(DATE_FMT);
    }

    private String latestBusinessDate(TableDef def) {
        String column = def.latestDateColumn();
        if (column == null || column.isBlank()) {
            return null;
        }
        return tushareTableMapper.maxValue(def.tableName(), column);
    }

    private List<String> resolveTradeDates(String start, String end) {
        List<String> dates = tushareLookupMapper.selectSseOpenTradeDates(start, end);
        if (dates.isEmpty()) {
            dates = tushareLookupMapper.selectAnyOpenTradeDates(start, end);
        }
        if (dates.isEmpty()) {
            log.warn("trade_cal 无 {}~{} 交易日数据，回退为单日 {}", start, end, end);
            dates = List.of(end);
        }
        return dates;
    }

    private List<YearMonth> monthsBetween(String start, String end) {
        LocalDate startDate = LocalDate.parse(start, DATE_FMT);
        LocalDate endDate = LocalDate.parse(end, DATE_FMT);
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.from(startDate);
        YearMonth last = YearMonth.from(endDate);
        while (!cursor.isAfter(last) && months.size() < MAX_MONTHS) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }

    private List<String> quarterEnds(String start, String end) {
        LocalDate startDate = LocalDate.parse(start, DATE_FMT);
        LocalDate endDate = LocalDate.parse(end, DATE_FMT);
        List<String> result = new ArrayList<>();
        int year = startDate.getYear();
        while (year <= endDate.getYear()) {
            for (String mmdd : List.of("0331", "0630", "0930", "1231")) {
                LocalDate q = LocalDate.parse(year + mmdd, DATE_FMT);
                if (!q.isBefore(startDate) && !q.isAfter(endDate)) {
                    result.add(q.format(DATE_FMT));
                }
            }
            year++;
        }
        return result;
    }

    private List<String> fridays(String start, String end) {
        LocalDate date = LocalDate.parse(start, DATE_FMT);
        LocalDate endDate = LocalDate.parse(end, DATE_FMT);
        while (date.getDayOfWeek().getValue() != 5) {
            date = date.plusDays(1);
        }
        List<String> result = new ArrayList<>();
        while (!date.isAfter(endDate)) {
            result.add(date.format(DATE_FMT));
            date = date.plusWeeks(1);
        }
        return result;
    }

    private List<String> resolveStockCodes(TableDef def) {
        Object source = def.options().get("codeSource");
        List<String> codes;
        if ("index_basic".equals(source)) {
            codes = tushareLookupMapper.selectIndexCodes();
        } else if ("sw_industry".equals(source)) {
            codes = tushareLookupMapper.selectL1IndustryCodes();
        } else {
            codes = tushareLookupMapper.selectListedStockCodes();
        }
        if (!codes.isEmpty()) {
            return codes;
        }
        if ("index_basic".equals(source)) {
            log.warn("index_basic 为空，回退内置核心宽基指数清单（{} 只）", CORE_INDEX_CODES.size());
            return CORE_INDEX_CODES;
        }
        return List.of();
    }

    private List<String> queryL1IndexCodes() {
        try {
            return tushareLookupMapper.selectL1IndustryCodes();
        } catch (Exception e) {
            log.warn("读取 sw_industry L1 代码失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** income/balancesheet/cashflow：同 (ts_code,end_date,report_type) 保留最新 ann_date。 */
    private List<Map<String, Object>> dedupKeepLatestAnnDate(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("ts_code") + "|" + row.get("end_date") + "|" + row.get("report_type");
            Map<String, Object> existing = latest.get(key);
            if (existing == null || compareDate(row.get("ann_date"), existing.get("ann_date")) > 0) {
                latest.put(key, row);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private static int compareDate(Object a, Object b) {
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static String maxDate(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    private String today() {
        return LocalDate.now(clock).format(DATE_FMT);
    }
}
