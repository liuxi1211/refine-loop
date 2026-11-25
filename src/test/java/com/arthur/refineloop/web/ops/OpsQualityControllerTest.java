package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.config.QualityProperties;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.quality.DataQualityGate;
import com.arthur.refineloop.tushare.quality.QualityCheckLogStore;
import com.arthur.refineloop.ops.QualityOpsService;
import com.arthur.refineloop.tushare.quality.QualityReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.arthur.refineloop.data.mapper.QualityQueryMapper;

/**
 * Step6 quality 模块 standalone MockMvc 测试（SQLite 真 DataQualityGate + quality_check_log）。
 *
 * <p>覆盖契约 §9.3：GET /quality/latest（无历史 data:null）、POST /quality/run（立即执行）、
 * GET /quality/history（近 N 天 + 逐状态计数）；参数非法 400。
 */
class OpsQualityControllerTest {

    private static final String TRADE_DATE = "20261124";

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private QualityCheckLogStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T10:00:00Z");
        store = new QualityCheckLogStore(SchemaTestSupport.mapper(jdbc, QualityCheckLogMapper.class),
                new ObjectMapper());
        DataQualityGate gate = new DataQualityGate(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class),
                SchemaTestSupport.mapper(jdbc, QualityQueryMapper.class), new QualityProperties(), store, clock);
        mockMvc = OpsTestSupport.mockMvc(new QualityController(gate, store, new QualityOpsService(store, clock)));
    }

    @Test
    void latestReturnsNullWhenNoHistory() throws Exception {
        mockMvc.perform(get("/api/ops/quality/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void runExecutesS0AndLatestHistoryReflectIt() throws Exception {
        OpsTestSupport.seedTradingDay(jdbc, LocalDateTime.of(2026, 11, 24, 17, 0));

        mockMvc.perform(post("/api/ops/quality/run").param("tradeDate", TRADE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value(QualityReport.RESULT_PASS))
                .andExpect(jsonPath("$.data.tradeDate").value(TRADE_DATE))
                .andExpect(jsonPath("$.data.triggerType").value(DataQualityGate.TRIGGER_MANUAL))
                .andExpect(jsonPath("$.data.checkId").isNumber())
                .andExpect(jsonPath("$.data.items.length()").value(10))
                .andExpect(jsonPath("$.data.items[0].code").value("L1_CRITICAL_JOB_daily"))
                .andExpect(jsonPath("$.data.items[0].status").value("PASS"));

        mockMvc.perform(get("/api/ops/quality/latest"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value(QualityReport.RESULT_PASS))
                .andExpect(jsonPath("$.data.tradeDate").value(TRADE_DATE));

        mockMvc.perform(get("/api/ops/quality/history"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value(QualityReport.RESULT_PASS))
                .andExpect(jsonPath("$.data.items[0].passCount").value(10))
                .andExpect(jsonPath("$.data.items[0].warnCount").value(0))
                .andExpect(jsonPath("$.data.items[0].failCount").value(0))
                .andExpect(jsonPath("$.data.items[0].checkTime").value("2026-11-24T18:00:00"));
    }

    @Test
    void runOnNonTradingDayReturnsEmpty() throws Exception {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', '20261122', '0')");

        mockMvc.perform(post("/api/ops/quality/run").param("tradeDate", "20261122"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value(QualityReport.RESULT_EMPTY))
                .andExpect(jsonPath("$.data.items[0].status").value("SKIP"));
    }

    @Test
    void runWithoutTradeDateUsesToday() throws Exception {
        OpsTestSupport.seedTradingDay(jdbc, LocalDateTime.of(2026, 11, 24, 17, 0));

        mockMvc.perform(post("/api/ops/quality/run"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tradeDate").value(TRADE_DATE));
    }

    @Test
    void runRejectsInvalidTradeDate() throws Exception {
        mockMvc.perform(post("/api/ops/quality/run").param("tradeDate", "2026-11-24"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("yyyyMMdd")));
    }

    @Test
    void historyRejectsInvalidDays() throws Exception {
        mockMvc.perform(get("/api/ops/quality/history").param("days", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/ops/quality/history").param("size", "500"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void historyPagesMultipleChecks() throws Exception {
        // 直接落两条历史（模拟过去两次检查），验证分页与排序（最新优先）
        store.save(LocalDateTime.of(2026, 11, 23, 18, 0), "20261123", "ORCHESTRATED", "FAIL", 12,
                java.util.List.of(new com.arthur.refineloop.tushare.quality.QualityCheckItem(
                        "L2_DAILY_TIMELINESS", 2, "时效", "FAIL", "e", "a", "m")));
        store.save(LocalDateTime.of(2026, 11, 24, 18, 0), "20261124", "MANUAL", "PASS", 8,
                java.util.List.of(new com.arthur.refineloop.tushare.quality.QualityCheckItem(
                        "L1_CRITICAL_JOB_daily", 1, "作业", "PASS", "e", "a", "m")));

        mockMvc.perform(get("/api/ops/quality/history").param("days", "30").param("size", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value("PASS"))
                .andExpect(jsonPath("$.data.items[0].passCount").value(1));

        mockMvc.perform(get("/api/ops/quality/history").param("days", "30").param("page", "2").param("size", "1"))
                .andExpect(jsonPath("$.data.items[0].result").value("FAIL"))
                .andExpect(jsonPath("$.data.items[0].failCount").value(1))
                .andExpect(jsonPath("$.data.items[0].triggerType").value("ORCHESTRATED"));
    }
}
