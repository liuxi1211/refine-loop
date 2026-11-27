package com.arthur.refineloop.tushare.quality;

import com.arthur.refineloop.config.QualityProperties;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.arthur.refineloop.data.mapper.QualityQueryMapper;

/**
 * T5 验收（SQLite）：DataQualityGate 双层判定的四类关键路径。
 *
 * <p>覆盖契约 §8 验收要求：删掉当日 daily 部分行 → FAIL 且明细列出具体项；非交易日 → EMPTY 不算失败；
 * adj_factor 缺行 → 检出；正常数据 → PASS / PASS_WITH_WARN；交易日无数据 → FAIL；结果落 quality_check_log。
 */
class DataQualityGateTest {

    /** 检查日（Asia/Shanghai；固定时钟 2026-11-24T10:00Z = 北京时间 18:00）。 */
    private static final String TRADE_DATE = "20261124";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 18, 0, 0);
    private static final List<String> CRITICAL_TABLES =
            List.of("daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal");

    private JdbcTemplate jdbc;
    private QualityCheckLogStore store;
    private DataQualityGate gate;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T10:00:00Z");
        store = new QualityCheckLogStore(SchemaTestSupport.mapper(jdbc, QualityCheckLogMapper.class),
                new ObjectMapper());
        gate = new DataQualityGate(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class),
                SchemaTestSupport.mapper(jdbc, QualityQueryMapper.class), new QualityProperties(), store, clock);
    }

    // ================================================================ 正常 / 告警

    @Test
    void normalTradingDayPassesAndPersists() {
        seedTradingDay();

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_PASS);
        assertThat(report.tradeDate()).isEqualTo(TRADE_DATE);
        assertThat(report.triggerType()).isEqualTo(DataQualityGate.TRIGGER_MANUAL);
        assertThat(report.checkId()).isPositive();
        assertThat(report.checkTime()).isEqualTo(NOW);
        assertThat(report.items()).hasSize(10);
        assertThat(report.items()).allMatch(item -> !QualityCheckItem.FAIL.equals(item.status()));
        assertThat(report.items()).allMatch(item -> !QualityCheckItem.WARN.equals(item.status()));

        // 落库 + 读回（tradeDate 编码在 items JSON 中，schema 无 trade_date 列）
        Long logged = jdbc.queryForObject("SELECT COUNT(*) FROM quality_check_log", Long.class);
        assertThat(logged).isEqualTo(1L);
        QualityReport latest = store.latest().orElseThrow();
        assertThat(latest.checkId()).isEqualTo(report.checkId());
        assertThat(latest.result()).isEqualTo(QualityReport.RESULT_PASS);
        assertThat(latest.tradeDate()).isEqualTo(TRADE_DATE);
        assertThat(latest.items()).hasSize(10);
        assertThat(latest.items()).extracting(QualityCheckItem::code)
                .contains("L1_CRITICAL_JOB_daily", "L2_ROW_COUNT_DEVIATION", "L2_ADJ_FACTOR_ALIGN");
    }

    @Test
    void closeNonPositiveIsWarnAndResultPassWithWarn() {
        seedTradingDay();
        jdbc.update("UPDATE daily SET close = 0 WHERE ts_code = '000000.SZ'");

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_PASS_WITH_WARN);
        assertThat(item(report, "L2_CLOSE_SANITY").status()).isEqualTo(QualityCheckItem.WARN);
        assertThat(item(report, "L2_CLOSE_SANITY").message()).contains("close ≤ 0");
        assertThat(report.count(QualityCheckItem.FAIL)).isZero();
    }

    // ================================================================ 第①层失败

    @Test
    void criticalJobNotSuccessFailsWithPerTableItem() {
        seedTradingDay();
        jdbc.update("UPDATE ingest_log SET status = 'FAILED' WHERE table_name = 'daily'");

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        QualityCheckItem dailyJob = item(report, "L1_CRITICAL_JOB_daily");
        assertThat(dailyJob.layer()).isEqualTo(1);
        assertThat(dailyJob.status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(dailyJob.actual()).contains("FAILED");
    }

    @Test
    void missingCriticalJobFails() {
        seedTradingDay();
        jdbc.update("DELETE FROM ingest_log WHERE table_name = 'adj_factor'");

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        QualityCheckItem adjJob = item(report, "L1_CRITICAL_JOB_adj_factor");
        assertThat(adjJob.status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(adjJob.message()).contains("作业缺失");
    }

    // ================================================================ 第②层失败

    @Test
    void partialDailyRowsFailWithDeviationItem() {
        seedTradingDay();
        // 删掉 3 只中的 2 只 → 偏差 2/3 ≈ 66.7% > 2%
        jdbc.update("DELETE FROM daily WHERE ts_code IN ('000000.SZ', '000001.SZ')");

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        QualityCheckItem deviation = item(report, "L2_ROW_COUNT_DEVIATION");
        assertThat(deviation.status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(deviation.actual()).contains("daily=1").contains("在市=3");
        assertThat(deviation.expected()).contains("2.00%");
        // daily 少行导致 adj_factor 行数不再一致 → 同样检出
        assertThat(item(report, "L2_ADJ_FACTOR_ALIGN").status()).isEqualTo(QualityCheckItem.FAIL);
        // 明细落库可追溯
        assertThat(store.latest().orElseThrow().count(QualityCheckItem.FAIL)).isEqualTo(2L);
    }

    @Test
    void missingAdjFactorRowsDetected() {
        seedTradingDay();
        jdbc.update("DELETE FROM adj_factor WHERE trade_date = ?", TRADE_DATE);

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        QualityCheckItem adj = item(report, "L2_ADJ_FACTOR_ALIGN");
        assertThat(adj.status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(adj.message()).contains("缺少 3 行");
        // daily 行数本身正常（偏差项 PASS），证明是独立检出
        assertThat(item(report, "L2_ROW_COUNT_DEVIATION").status()).isEqualTo(QualityCheckItem.PASS);
    }

    @Test
    void tradingDayWithoutDailyDataFails() {
        // 交易日历标记开市但无任何行情数据 → FAIL（区别于 EMPTY）
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')", TRADE_DATE);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES (?, ?, 'L')",
                    String.format("00000%d.SZ", i), "股票" + i);
        }

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        assertThat(item(report, "L2_DAILY_TIMELINESS").status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(item(report, "L2_ROW_COUNT_DEVIATION").status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(report.items()).anyMatch(item -> QualityCheckItem.FAIL.equals(item.status()));
    }

    @Test
    void staleFinaIndicatorIsWarn() {
        seedTradingDay();
        // 最新公告日距今 90 天 > 30 天阈值 → WARN
        jdbc.update("UPDATE fina_indicator SET ann_date = '20260826'");

        QualityReport report = gate.check(TRADE_DATE);

        assertThat(item(report, "L2_FINA_INDICATOR_TIMELINESS").status())
                .isEqualTo(QualityCheckItem.WARN);
        assertThat(report.result()).isEqualTo(QualityReport.RESULT_PASS_WITH_WARN);
    }

    // ================================================================ 非交易日

    @Test
    void nonTradingDayIsEmptyWithAllSkipped() {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '0')",
                "20261122");

        QualityReport report = gate.check("20261122");

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_EMPTY);
        assertThat(report.items()).hasSize(10);
        assertThat(report.items()).allMatch(item -> QualityCheckItem.SKIP.equals(item.status()));
        assertThat(store.latest().orElseThrow().result()).isEqualTo(QualityReport.RESULT_EMPTY);
        assertThat(store.latest().orElseThrow().tradeDate()).isEqualTo("20261122");
    }

    @Test
    void noCalendarAndNoDataFailsInsteadOfPretendingEmpty() {
        QualityReport report = gate.check("20261123");

        assertThat(report.result()).isEqualTo(QualityReport.RESULT_FAIL);
        assertThat(item(report, "L0_TRADE_CALENDAR").status()).isEqualTo(QualityCheckItem.FAIL);
        assertThat(item(report, "L0_TRADE_CALENDAR").message()).contains("请先回填 trade_cal");
    }

    // ================================================================ 参数与辅助

    @Test
    void nullTradeDateUsesTodayInShanghai() {
        seedTradingDay();

        QualityReport report = gate.check(null);

        assertThat(report.tradeDate()).isEqualTo(TRADE_DATE);
        assertThat(report.result()).isEqualTo(QualityReport.RESULT_PASS);
    }

    @Test
    void invalidTradeDateFormatRejected() {
        assertThatThrownBy(() -> gate.check("2026-11-24"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyyMMdd");
        assertThatThrownBy(() -> gate.check(TRADE_DATE, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerType");
    }

    @Test
    void orchestratedTriggerTypePersisted() {
        seedTradingDay();

        QualityReport report = gate.check(TRADE_DATE, DataQualityGate.TRIGGER_ORCHESTRATED);

        assertThat(report.triggerType()).isEqualTo(DataQualityGate.TRIGGER_ORCHESTRATED);
        assertThat(store.latest().orElseThrow().triggerType())
                .isEqualTo(DataQualityGate.TRIGGER_ORCHESTRATED);
    }

    // ================================================================ 种子与断言辅助

    private void seedTradingDay() {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')",
                TRADE_DATE);
        for (int i = 0; i < 3; i++) {
            String code = String.format("00000%d.SZ", i);
            jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES (?, ?, 'L')",
                    code, "股票" + i);
            jdbc.update("INSERT INTO daily (ts_code, trade_date, close) VALUES (?, ?, ?)",
                    code, TRADE_DATE, 10.0D + i);
            jdbc.update("INSERT INTO adj_factor (ts_code, trade_date, adj_factor) VALUES (?, ?, 1.0)",
                    code, TRADE_DATE);
            jdbc.update("INSERT INTO daily_basic (ts_code, trade_date) VALUES (?, ?)", code, TRADE_DATE);
        }
        jdbc.update("INSERT INTO fina_indicator (ts_code, ann_date, end_date)"
                + " VALUES ('000000.SZ', '20261118', '20260930')");
        for (String table : CRITICAL_TABLES) {
            insertSuccessJob(table);
        }
    }

    private void insertSuccessJob(String table) {
        jdbc.update("INSERT INTO ingest_log"
                        + " (run_id, table_name, job_type, range_params, status, created_at)"
                        + " VALUES ('R20261124-01', ?, 'INCR', ?, 'SUCCESS', ?)",
                table, TRADE_DATE, Timestamp.valueOf(NOW.minusMinutes(20)));
    }

    private static QualityCheckItem item(QualityReport report, String code) {
        return report.items().stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到检查项: " + code + "，实际: "
                        + report.items().stream().map(QualityCheckItem::code).toList()));
    }
}
