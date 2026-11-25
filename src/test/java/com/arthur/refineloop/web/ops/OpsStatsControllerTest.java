package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.service.IngestLogService;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.service.SysConfigService;
import com.arthur.refineloop.tushare.quality.QualityCheckItem;
import com.arthur.refineloop.tushare.quality.QualityCheckLogStore;
import com.arthur.refineloop.ops.StatsService;
import com.arthur.refineloop.ops.TableOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.OpsStatsMapper;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.arthur.refineloop.data.mapper.SysConfigMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;

/**
 * Step6 stats 模块 standalone MockMvc 测试（SQLite 真表 + 测试桩 IngestFacade）。
 *
 * <p>覆盖契约 §9.4：今日作业计数（不含 SKIPPED）、rate_limit_counter 今日调用合计、
 * 表健康度 41 表汇总、最近质量门结果、最近编排摘要。
 */
class OpsStatsControllerTest {

    private static final String TODAY = "20261124";

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private OpsTestSupport.FakeIngestFacade facade;
    private QualityCheckLogStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T10:00:00Z");
        LocalDateTime jobTime = LocalDateTime.of(2026, 11, 24, 17, 0);
        OpsTestSupport.seedTradingDay(jdbc, jobTime);
        // 追加失败/运行中/跳过作业各 1 条（SKIPPED 不计入今日作业总数）
        OpsTestSupport.insertJob(jdbc, "daily", "INCR", "FAILED", TODAY, jobTime.plusMinutes(5));
        OpsTestSupport.insertJob(jdbc, "daily", "INCR", "RUNNING", TODAY, jobTime.plusMinutes(10));
        OpsTestSupport.insertJob(jdbc, "hk_hold", "INCR", "SKIPPED", TODAY, jobTime.plusMinutes(15));
        // 今日 Tushare 调用计数
        jdbc.update("INSERT INTO rate_limit_counter (api_name, minute_slot, call_count)"
                + " VALUES ('daily', '202611241700', 100)");
        jdbc.update("INSERT INTO rate_limit_counter (api_name, minute_slot, call_count)"
                + " VALUES ('adj_factor', '202611241701', 34)");
        // 最近质量门结果
        store = new QualityCheckLogStore(SchemaTestSupport.mapper(jdbc, QualityCheckLogMapper.class),
                new ObjectMapper());
        store.save(LocalDateTime.of(2026, 11, 24, 18, 0), TODAY, "ORCHESTRATED",
                "PASS_WITH_WARN", 15, List.of(
                        new QualityCheckItem("L1_CRITICAL_JOB_daily", 1, "作业", "PASS", "e", "a", "m"),
                        new QualityCheckItem("L2_CLOSE_SANITY", 2, "sanity", "WARN", "e", "a", "m")));

        facade = new OpsTestSupport.FakeIngestFacade();
        facade.progress = new OrchestrationProgress(false, "R20261124-01", "SUCCESS",
                jobTime.minusHours(1), jobTime.plusMinutes(40), "DAILY", List.of(),
                41, 40, 1, 0, 0, 97);

        IngestLogService logService = new IngestLogService(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class), clock);
        SysConfigService sysConfigService = new SysConfigService(SchemaTestSupport.mapper(jdbc, SysConfigMapper.class), clock);
        TableOpsService tableOpsService = new TableOpsService(
                SchemaTestSupport.mapper(jdbc, TushareTableMapper.class), clock, sysConfigService, logService);
        StatsService statsService = new StatsService(SchemaTestSupport.mapper(jdbc, OpsStatsMapper.class),
                clock, tableOpsService, facade, store);
        mockMvc = OpsTestSupport.mockMvc(new StatsController(statsService));
    }

    @Test
    void todayAggregatesJobsCallsTablesQualityAndOrchestration() throws Exception {
        mockMvc.perform(get("/api/ops/stats/today"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.date").value(TODAY))
                // 5 SUCCESS + 1 FAILED + 1 RUNNING；SKIPPED 不计入 total
                .andExpect(jsonPath("$.data.jobs.total").value(7))
                .andExpect(jsonPath("$.data.jobs.success").value(5))
                .andExpect(jsonPath("$.data.jobs.failed").value(1))
                .andExpect(jsonPath("$.data.jobs.running").value(1))
                .andExpect(jsonPath("$.data.jobs.pending").value(0))
                .andExpect(jsonPath("$.data.tushareCallsToday").value(134))
                .andExpect(jsonPath("$.data.tables.total").value(41))
                .andExpect(jsonPath("$.data.quality.result").value("PASS_WITH_WARN"))
                .andExpect(jsonPath("$.data.quality.checkTime").value("2026-11-24T18:00:00"))
                .andExpect(jsonPath("$.data.lastOrchestration.runId").value("R20261124-01"))
                .andExpect(jsonPath("$.data.lastOrchestration.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.lastOrchestration.startedAt").value("2026-11-24T16:00:00"));
    }

    @Test
    void idleOrchestrationReturnsNullLastOrchestration() throws Exception {
        facade.progress = new OrchestrationProgress(false, "-", "IDLE", null, null, "NONE",
                List.of(), 0, 0, 0, 0, 0, 0);

        mockMvc.perform(get("/api/ops/stats/today"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.lastOrchestration").value(nullValue()));
    }

    @Test
    void emptyDatabaseStillReturnsZeroedStats() throws Exception {
        SchemaTestSupport.reset(jdbc);

        mockMvc.perform(get("/api/ops/stats/today"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobs.total").value(0))
                .andExpect(jsonPath("$.data.tushareCallsToday").value(0))
                .andExpect(jsonPath("$.data.tables.total").value(41))
                .andExpect(jsonPath("$.data.quality").value(nullValue()));
    }
}
