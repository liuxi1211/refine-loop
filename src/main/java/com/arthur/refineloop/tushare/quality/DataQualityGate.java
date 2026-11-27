package com.arthur.refineloop.tushare.quality;

import com.arthur.refineloop.config.QualityProperties;
import com.arthur.refineloop.data.entity.IngestLogEntity;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.QualityQueryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * T5 数据质量门（S0 双层判定）。
 *
 * <p>第①层：关键表（默认 daily/adj_factor/daily_basic/fina_indicator/trade_cal，可配）当日
 * {@code ingest_log} 最新作业必须 {@code SUCCESS}（FAILED/RUNNING/SKIPPED/缺失 → FAIL 项）。
 *
 * <p>第②层：daily 当日行数 vs stock_basic 在市(L)总数偏差 ≤ 阈值；
 * adj_factor 与 daily 同日行数一致；daily.close &lt;= 0 行数告警；
 * daily 最新 trade_date == 检查日；fina_indicator 最新 ann_date 距检查日 ≤ N 天（超限 WARN）。
 *
 * <p>结果区分：非交易日（trade_cal is_open='0'）→ 整体 {@code EMPTY}、逐项 {@code SKIP}，不算失败；
 * 日历缺失/非法 → {@code FAIL}（空库不能被推断为非交易日）；交易日但无数据 → {@code FAIL}。结果落 {@code quality_check_log}。
 *
 * <p>SQL 只使用固定表名/列名（daily/adj_factor/trade_cal/stock_basic/fina_indicator/ingest_log），
 * 全部收敛在 {@link QualityQueryMapper}（固定表查询）与 {@link IngestLogMapper}（作业日志）里，
 * 本类只做判定逻辑，不再持有任何 SQL 字符串。
 */
@Component
public class DataQualityGate {

    private static final Logger log = LoggerFactory.getLogger(DataQualityGate.class);

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_ORCHESTRATED = "ORCHESTRATED";
    public static final String TRIGGER_RETRY = "RETRY";
    private static final Set<String> TRIGGER_TYPES = Set.of(TRIGGER_MANUAL, TRIGGER_ORCHESTRATED,
            TRIGGER_RETRY);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 第①层检查项 code 前缀（后接表名）。 */
    private static final String CODE_JOB_PREFIX = "L1_CRITICAL_JOB_";
    private static final String CODE_CALENDAR = "L0_TRADE_CALENDAR";
    private static final String CODE_ROW_COUNT = "L2_ROW_COUNT_DEVIATION";
    private static final String CODE_ADJ_ALIGN = "L2_ADJ_FACTOR_ALIGN";
    private static final String CODE_CLOSE_SANITY = "L2_CLOSE_SANITY";
    private static final String CODE_DAILY_TIMELINESS = "L2_DAILY_TIMELINESS";
    private static final String CODE_FINA_TIMELINESS = "L2_FINA_INDICATOR_TIMELINESS";

    private static final String STATUS_SUCCESS = "SUCCESS";

    private final IngestLogMapper ingestLogMapper;
    private final QualityQueryMapper qualityQueryMapper;
    private final QualityProperties properties;
    private final QualityCheckLogStore checkLogStore;
    private final Clock clock;

    public DataQualityGate(IngestLogMapper ingestLogMapper, QualityQueryMapper qualityQueryMapper,
                           QualityProperties properties, QualityCheckLogStore checkLogStore, Clock clock) {
        this.ingestLogMapper = ingestLogMapper;
        this.qualityQueryMapper = qualityQueryMapper;
        this.properties = properties;
        this.checkLogStore = checkLogStore;
        this.clock = clock;
    }

    /** 默认触发类型 MANUAL；tradeDate 为空取今天（Asia/Shanghai）。 */
    public QualityReport check(String tradeDate) {
        return check(tradeDate, TRIGGER_MANUAL);
    }

    /**
     * 执行一次 S0 双层判定并落库。
     *
     * @param tradeDate   业务日期 yyyyMMdd；null/空白取今天（Asia/Shanghai）
     * @param triggerType MANUAL / ORCHESTRATED / RETRY；null/空白按 MANUAL
     */
    public QualityReport check(String tradeDate, String triggerType) {
        long startNanos = System.nanoTime();
        LocalDateTime checkTime = LocalDateTime.now(clock);
        String date = normalizeTradeDate(tradeDate, checkTime);
        String trigger = normalizeTrigger(triggerType);

        long dailyCount = qualityQueryMapper.countDailyByDate(date);
        String isOpen = queryTradeCalIsOpen(date);
        boolean nonTradingDay = "0".equals(isOpen);

        List<QualityCheckItem> items;
        String result;
        if (isOpen == null || (!"1".equals(isOpen) && !nonTradingDay)) {
            items = List.of(new QualityCheckItem(CODE_CALENDAR, 0,
                    "第⓪层 · 交易日历可用性", QualityCheckItem.FAIL,
                    "trade_cal 当日记录为 1（开市）或 0（休市）",
                    isOpen == null ? "无当日日历记录" : "is_open=" + isOpen,
                    "无法证明当日为非交易日；禁止把『空库/日历缺失』误报为 EMPTY，请先回填 trade_cal"));
            result = QualityReport.RESULT_FAIL;
            log.warn("S0 质量门: tradeDate={} 交易日历缺失或非法 → FAIL（不能推断为非交易日）", date);
        } else if (nonTradingDay) {
            items = skipItems(date, isOpen);
            result = QualityReport.RESULT_EMPTY;
            log.info("S0 质量门: tradeDate={} 非交易日 → EMPTY（逐项 SKIP，不算失败）", date);
        } else {
            items = new ArrayList<>();
            items.addAll(checkCriticalJobs(date, checkTime));
            items.addAll(checkRowCountAndSanity(date, dailyCount));
            items.addAll(checkTimeliness(date));
            result = resolveResult(items);
            log.info("S0 质量门: tradeDate={} result={} 检查项 {} 个", date, result, items.size());
        }

        long durationMs = Math.max(0L, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        long checkId = checkLogStore.save(checkTime, date, trigger, result, durationMs, items);
        return new QualityReport(checkId, checkTime, date, trigger, result, durationMs, List.copyOf(items));
    }

    // ------------------------------------------------------------------ 第①层

    private List<QualityCheckItem> checkCriticalJobs(String date, LocalDateTime checkTime) {
        List<String> tables = properties.getCriticalTables();
        if (tables == null || tables.isEmpty()) {
            tables = List.of("daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal");
        }
        List<QualityCheckItem> items = new ArrayList<>(tables.size());
        for (String table : tables) {
            if (table == null || table.isBlank()) {
                continue;
            }
            String trimmed = table.trim();
            String code = CODE_JOB_PREFIX + trimmed;
            String name = "第①层 · 关键表作业状态（" + trimmed + "）";
            Optional<JobSnapshot> job = latestJob(trimmed, date, checkTime);
            if (job.isEmpty()) {
                items.add(new QualityCheckItem(code, 1, name, QualityCheckItem.FAIL,
                        "当日最新作业 SUCCESS", "无作业记录",
                        "关键表 " + trimmed + " 当日无 ingest_log 记录（作业缺失）"));
                continue;
            }
            JobSnapshot snapshot = job.get();
            String status = snapshot.status() == null ? "UNKNOWN" : snapshot.status();
            String actual = status + (snapshot.createdAt() == null ? ""
                    : " @" + snapshot.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            if (STATUS_SUCCESS.equals(status)) {
                items.add(new QualityCheckItem(code, 1, name, QualityCheckItem.PASS,
                        "当日最新作业 SUCCESS", actual, "关键表 " + trimmed + " 当日最新作业成功"));
            } else {
                items.add(new QualityCheckItem(code, 1, name, QualityCheckItem.FAIL,
                        "当日最新作业 SUCCESS", actual,
                        "关键表 " + trimmed + " 最新作业状态为 " + status + "，S0 要求 SUCCESS"));
            }
        }
        return items;
    }

    /** 当日（检查执行日）该表最新作业；手工补检历史日期时回退到 range_params 含该日期的最近作业。 */
    private Optional<JobSnapshot> latestJob(String table, String tradeDate, LocalDateTime checkTime) {
        String today = LocalDate.now(clock.withZone(ZONE)).format(DATE_FORMAT);
        IngestLogEntity entity;
        if (today.equals(tradeDate)) {
            entity = ingestLogMapper.selectLatestByTableSince(table, checkTime.toLocalDate().atStartOfDay());
        } else {
            entity = ingestLogMapper.selectLatestByTableRange(table, "%" + tradeDate + "%");
        }
        return Optional.ofNullable(entity)
                .map(row -> new JobSnapshot(row.getStatus(), row.getCreatedAt()));
    }

    // ------------------------------------------------------------------ 第②层

    private List<QualityCheckItem> checkRowCountAndSanity(String date, long dailyCount) {
        List<QualityCheckItem> items = new ArrayList<>(3);

        // 行数偏差：daily 当日行数 vs stock_basic 在市(L)总数
        long listedCount = qualityQueryMapper.countListedStocks();
        double threshold = normalizedThreshold();
        String deviationExpected = "偏差 ≤ " + formatPercent(threshold);
        if (listedCount <= 0L) {
            items.add(new QualityCheckItem(CODE_ROW_COUNT, 2, "第②层 · 行数一致性（daily vs stock_basic）",
                    QualityCheckItem.FAIL, deviationExpected, "daily=" + dailyCount + ", 在市=0",
                    "stock_basic 在市(L)股票数为 0，无法校验 daily 行数偏差"));
        } else {
            double deviation = Math.abs(dailyCount - listedCount) / (double) listedCount;
            String actual = String.format("daily=%d, 在市=%d, 偏差=%.2f%%",
                    dailyCount, listedCount, deviation * 100.0D);
            if (deviation <= threshold) {
                items.add(new QualityCheckItem(CODE_ROW_COUNT, 2, "第②层 · 行数一致性（daily vs stock_basic）",
                        QualityCheckItem.PASS, deviationExpected, actual,
                        "daily 行数与在市股票数偏差在阈值内（停牌/新股/退市等正常差异）"));
            } else {
                items.add(new QualityCheckItem(CODE_ROW_COUNT, 2, "第②层 · 行数一致性（daily vs stock_basic）",
                        QualityCheckItem.FAIL, deviationExpected, actual,
                        "daily 当日行数偏差超阈值，可能行情数据缺失/不完整（停牌+退市正常差异不应超过阈值）"));
            }
        }

        // 复权因子对齐：adj_factor 与 daily 同日行数一致
        long adjCount = qualityQueryMapper.countAdjFactorByDate(date);
        String adjExpected = "adj_factor 行数 = daily 行数（" + dailyCount + "）";
        if (adjCount == dailyCount) {
            items.add(new QualityCheckItem(CODE_ADJ_ALIGN, 2, "第②层 · 复权因子对齐",
                    QualityCheckItem.PASS, adjExpected, "adj_factor=" + adjCount,
                    "adj_factor 与 daily 同日行数一致"));
        } else {
            String delta = adjCount < dailyCount
                    ? "缺少 " + (dailyCount - adjCount) + " 行"
                    : "多出 " + (adjCount - dailyCount) + " 行";
            items.add(new QualityCheckItem(CODE_ADJ_ALIGN, 2, "第②层 · 复权因子对齐",
                    QualityCheckItem.FAIL, adjExpected, "adj_factor=" + adjCount,
                    "adj_factor 与 daily 同日行数不一致（" + delta + "），复权计算将失真"));
        }

        // 字段 sanity：daily.close <= 0
        long badClose = qualityQueryMapper.countNonPositiveCloseByDate(date);
        if (badClose > 0L) {
            items.add(new QualityCheckItem(CODE_CLOSE_SANITY, 2, "第②层 · 字段 sanity（daily.close）",
                    QualityCheckItem.WARN, "close ≤ 0 行数 = 0", "close ≤ 0 行数 = " + badClose,
                    "daily 存在 " + badClose + " 行 close ≤ 0 的脏数据（已告警，需隔离/复检）"));
        } else {
            items.add(new QualityCheckItem(CODE_CLOSE_SANITY, 2, "第②层 · 字段 sanity（daily.close）",
                    QualityCheckItem.PASS, "close ≤ 0 行数 = 0", "close ≤ 0 行数 = 0",
                    "daily 收盘价无 close ≤ 0 异常行"));
        }
        return items;
    }

    private List<QualityCheckItem> checkTimeliness(String date) {
        List<QualityCheckItem> items = new ArrayList<>(2);

        // daily 最新 trade_date == 检查日
        String latestDaily = qualityQueryMapper.maxDailyTradeDate();
        if (date.equals(latestDaily)) {
            items.add(new QualityCheckItem(CODE_DAILY_TIMELINESS, 2, "关键表时效 · daily 最新交易日",
                    QualityCheckItem.PASS, "最新 trade_date = " + date, "最新 trade_date = " + latestDaily,
                    "daily 数据已更新到检查日"));
        } else {
            String actual = latestDaily == null ? "无数据" : "最新 trade_date = " + latestDaily;
            items.add(new QualityCheckItem(CODE_DAILY_TIMELINESS, 2, "关键表时效 · daily 最新交易日",
                    QualityCheckItem.FAIL, "最新 trade_date = " + date, actual,
                    "daily 最新 trade_date 落后于检查日，当日行情可能未入库"));
        }

        // fina_indicator 最新 ann_date 距今 ≤ N 天（超限 WARN）
        int maxLagDays = properties.getFinaIndicatorMaxLagDays() <= 0 ? 30
                : properties.getFinaIndicatorMaxLagDays();
        String latestAnn = qualityQueryMapper.maxFinaIndicatorAnnDate();
        LocalDate checkDate = parseDate(date);
        if (latestAnn == null || latestAnn.isBlank()) {
            items.add(new QualityCheckItem(CODE_FINA_TIMELINESS, 2, "关键表时效 · fina_indicator 最新公告日",
                    QualityCheckItem.WARN, "最新 ann_date 距今 ≤ " + maxLagDays + " 天", "无数据",
                    "fina_indicator 表无数据，财务指标时效无法确认（告警）"));
        } else {
            LocalDate annDate = parseDate(latestAnn);
            long lagDays = annDate == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(annDate, checkDate);
            String actual = "最新 ann_date = " + latestAnn + "（距检查日 " + lagDays + " 天）";
            if (annDate == null) {
                items.add(new QualityCheckItem(CODE_FINA_TIMELINESS, 2, "关键表时效 · fina_indicator 最新公告日",
                        QualityCheckItem.WARN, "最新 ann_date 距今 ≤ " + maxLagDays + " 天", actual,
                        "fina_indicator 最新 ann_date 无法解析（" + latestAnn + "），时效告警"));
            } else if (lagDays > maxLagDays) {
                items.add(new QualityCheckItem(CODE_FINA_TIMELINESS, 2, "关键表时效 · fina_indicator 最新公告日",
                        QualityCheckItem.WARN, "最新 ann_date 距今 ≤ " + maxLagDays + " 天", actual,
                        "fina_indicator 最新公告日距今 " + lagDays + " 天，超过阈值 " + maxLagDays + " 天（告警）"));
            } else {
                items.add(new QualityCheckItem(CODE_FINA_TIMELINESS, 2, "关键表时效 · fina_indicator 最新公告日",
                        QualityCheckItem.PASS, "最新 ann_date 距今 ≤ " + maxLagDays + " 天", actual,
                        "fina_indicator 公告时效在阈值内"));
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ 非交易日

    private List<QualityCheckItem> skipItems(String date, String isOpen) {
        String actual = isOpen == null ? "无交易日历且当日无 daily 数据" : "is_open=" + isOpen;
        String message = "非交易日/当日无数据：按 SKIP 处理，不误报失败（EMPTY）";
        List<QualityCheckItem> items = new ArrayList<>();
        List<String> tables = properties.getCriticalTables();
        if (tables == null || tables.isEmpty()) {
            tables = List.of("daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal");
        }
        for (String table : tables) {
            if (table == null || table.isBlank()) {
                continue;
            }
            String trimmed = table.trim();
            items.add(new QualityCheckItem(CODE_JOB_PREFIX + trimmed, 1,
                    "第①层 · 关键表作业状态（" + trimmed + "）", QualityCheckItem.SKIP,
                    "当日最新作业 SUCCESS", actual, message));
        }
        items.add(new QualityCheckItem(CODE_ROW_COUNT, 2,
                "第②层 · 行数一致性（daily vs stock_basic）", QualityCheckItem.SKIP,
                "偏差 ≤ " + formatPercent(normalizedThreshold()), actual, message));
        items.add(new QualityCheckItem(CODE_ADJ_ALIGN, 2, "第②层 · 复权因子对齐", QualityCheckItem.SKIP,
                "adj_factor 行数 = daily 行数", actual, message));
        items.add(new QualityCheckItem(CODE_CLOSE_SANITY, 2, "第②层 · 字段 sanity（daily.close）",
                QualityCheckItem.SKIP, "close ≤ 0 行数 = 0", actual, message));
        items.add(new QualityCheckItem(CODE_DAILY_TIMELINESS, 2, "关键表时效 · daily 最新交易日",
                QualityCheckItem.SKIP, "最新 trade_date = " + date, actual, message));
        items.add(new QualityCheckItem(CODE_FINA_TIMELINESS, 2, "关键表时效 · fina_indicator 最新公告日",
                QualityCheckItem.SKIP, "最新 ann_date 距今 ≤ " + properties.getFinaIndicatorMaxLagDays() + " 天",
                actual, message));
        return items;
    }

    // ------------------------------------------------------------------ helpers

    private static String resolveResult(List<QualityCheckItem> items) {
        boolean anyFail = items.stream().anyMatch(QualityCheckItem::isFail);
        if (anyFail) {
            return QualityReport.RESULT_FAIL;
        }
        boolean anyWarn = items.stream().anyMatch(QualityCheckItem::isWarn);
        return anyWarn ? QualityReport.RESULT_PASS_WITH_WARN : QualityReport.RESULT_PASS;
    }

    /** 交易日历当日开盘状态；先查 SSE，未命中再取任意交易所记录。 */
    private String queryTradeCalIsOpen(String tradeDate) {
        String sse = qualityQueryMapper.selectSseIsOpen(tradeDate);
        return sse != null ? sse : qualityQueryMapper.selectAnyIsOpen(tradeDate);
    }

    private double normalizedThreshold() {
        double threshold = properties.getRowCountDeviationThreshold();
        return threshold <= 0.0D || threshold >= 1.0D ? 0.02D : threshold;
    }

    private static String formatPercent(double ratio) {
        return String.format("%.2f%%", ratio * 100.0D);
    }

    private String normalizeTradeDate(String tradeDate, LocalDateTime checkTime) {
        if (tradeDate == null || tradeDate.isBlank()) {
            return LocalDate.now(clock.withZone(ZONE)).format(DATE_FORMAT);
        }
        String trimmed = tradeDate.trim();
        if (!trimmed.matches("\\d{8}") || parseDate(trimmed) == null) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd 格式: " + tradeDate);
        }
        return trimmed;
    }

    private static String normalizeTrigger(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) {
            return TRIGGER_MANUAL;
        }
        String normalized = triggerType.trim().toUpperCase();
        if (!TRIGGER_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "triggerType 仅支持 MANUAL/ORCHESTRATED/RETRY: " + triggerType);
        }
        return normalized;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || !value.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }



    private record JobSnapshot(String status, LocalDateTime createdAt) {
    }
}
