package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.service.IngestLogService;
import com.arthur.refineloop.ops.IngestOpsService;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.arthur.refineloop.data.mapper.IngestLogMapper;

/**
 * Step6 ingest 模块 standalone MockMvc 测试（SQLite 作业表 + 测试桩 IngestFacade）。
 *
 * <p>覆盖契约 §9.2：POST /ingest/daily（409 冲突）、orchestrations/current、jobs 筛选分页、
 * jobs/{id}/retry、backfill-plan；参数非法 400 / 未知表 404 透传。
 */
class OpsIngestControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private OpsTestSupport.FakeIngestFacade facade;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T10:00:00Z");
        facade = new OpsTestSupport.FakeIngestFacade();
        IngestLogService logService = new IngestLogService(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class), clock);
        mockMvc = OpsTestSupport.mockMvc(
                new IngestController(facade, new IngestOpsService(facade, logService)));
    }

    // ================================================================ 每日编排

    @Test
    void triggerDailyReturnsAcceptedAck() throws Exception {
        mockMvc.perform(post("/api/ops/ingest/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.runId").value("R20261124-01"));
        assertThat(facade.dailyCalls).isEqualTo(1);
    }

    @Test
    void triggerDailyConflictsWhenOrchestrationRunning() throws Exception {
        facade.dailyAck = new com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck(false, null,
                "已有编排正在运行，请等待结束或查看当前进度");

        mockMvc.perform(post("/api/ops/ingest/daily"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value(containsString("已有编排正在运行")));
    }

    @Test
    void currentOrchestrationPassesProgress() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 24, 18, 0);
        facade.progress = new OrchestrationProgress(true, "R20261124-01", "RUNNING", now.minusHours(1),
                null, "DAILY", List.of(), 41, 20, 1, 1, 19, 51);

        mockMvc.perform(get("/api/ops/ingest/orchestrations/current"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.runId").value("R20261124-01"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.percent").value(51))
                .andExpect(jsonPath("$.data.startedAt").value("2026-11-24T17:00:00"));
    }

    // ================================================================ 作业历史

    @Test
    void jobsSupportsStatusAndTableFilters() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 11, 24, 17, 0);
        OpsTestSupport.insertJob(jdbc, "daily", "INCR", "SUCCESS", "20261124", base);
        OpsTestSupport.insertJob(jdbc, "daily", "INCR", "FAILED", "20261123", base.plusHours(1));
        OpsTestSupport.insertJob(jdbc, "adj_factor", "INCR", "SUCCESS", "20261124", base.plusHours(2));

        mockMvc.perform(get("/api/ops/ingest/jobs"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        mockMvc.perform(get("/api/ops/ingest/jobs").param("table", "daily"))
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/ops/ingest/jobs").param("status", "failed"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].tableName").value("daily"));
    }

    @Test
    void jobsRejectsInvalidParams() throws Exception {
        mockMvc.perform(get("/api/ops/ingest/jobs").param("status", "WEIRD"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/ops/ingest/jobs").param("table", "not_a_table"))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/ops/ingest/jobs").param("size", "0"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void retryPassesJobIdThroughFacade() throws Exception {
        mockMvc.perform(post("/api/ops/ingest/jobs/42/retry"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true));
        assertThat(facade.retriedJobId).isEqualTo(42L);
    }

    @Test
    void retryBusinessErrorIsPassedThrough() throws Exception {
        facade.retryFailure = ApiException.badRequest("仅 FAILED 作业可重试，当前状态: RUNNING");

        mockMvc.perform(post("/api/ops/ingest/jobs/42/retry"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("仅 FAILED 作业可重试")));
    }

    @Test
    void retryNonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ops/ingest/jobs/abc/retry"))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================ 回填向导

    @Test
    void backfillPlanPassesModeAndTablesThroughFacade() throws Exception {
        mockMvc.perform(post("/api/ops/ingest/backfill-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"custom\",\"tables\":[\"daily\",\"income\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planId").value("BF-20261124-01"))
                .andExpect(jsonPath("$.data.mode").value("standard"))
                .andExpect(jsonPath("$.data.tables[0]").value("daily"))
                .andExpect(jsonPath("$.data.batches[0].night").value(1));

        assertThat(facade.lastMode).isEqualTo("custom");
        assertThat(facade.lastTables).containsExactly("daily", "income");
    }

    @Test
    void backfillPlanRejectsMalformedBody() throws Exception {
        mockMvc.perform(post("/api/ops/ingest/backfill-plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
