package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.service.IngestLogService;
import com.arthur.refineloop.tushare.ingest.service.SysConfigService;
import com.arthur.refineloop.ops.IngestOpsService;
import com.arthur.refineloop.ops.TableOpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.SysConfigMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;

/**
 * Step6 tables 模块 standalone MockMvc 测试（SQLite + 测试桩 IngestFacade，不启动 Spring 上下文）。
 *
 * <p>覆盖契约 §9.1：GET /tables、GET /tables/{table}、preview（关键列/全部列/过滤/分页保护）、
 * jobs、run、backfill、daily-toggle；表不存在 404、参数非法 400、编排冲突 409 透传。
 */
class OpsTablesControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private OpsTestSupport.FakeIngestFacade facade;
    private SysConfigService sysConfigService;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-24T10:00:00Z");
        OpsTestSupport.seedTradingDay(jdbc, LocalDateTime.of(2026, 11, 24, 17, 0));
        IngestLogService logService = new IngestLogService(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class), clock);
        sysConfigService = new SysConfigService(SchemaTestSupport.mapper(jdbc, SysConfigMapper.class), clock);
        TableOpsService tableOpsService = new TableOpsService(
                SchemaTestSupport.mapper(jdbc, TushareTableMapper.class), clock, sysConfigService, logService);
        facade = new OpsTestSupport.FakeIngestFacade();
        mockMvc = OpsTestSupport.mockMvc(
                new TablesController(tableOpsService, new IngestOpsService(facade, logService)));
    }

    // ================================================================ 总览 / 详情

    @Test
    void listTablesReturnsSummaryAnd41Metas() throws Exception {
        mockMvc.perform(get("/api/ops/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.total").value(41))
                .andExpect(jsonPath("$.data.summary.healthy").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.tables.length()").value(41))
                // 注册表顺序：stock_basic/trade_cal/stock_namechange/daily
                .andExpect(jsonPath("$.data.tables[3].table").value("daily"))
                .andExpect(jsonPath("$.data.tables[3].api").value("daily"))
                .andExpect(jsonPath("$.data.tables[3].chineseName").value("日线行情"))
                .andExpect(jsonPath("$.data.tables[3].group").value("行情"))
                .andExpect(jsonPath("$.data.tables[3].strategy").value("PER_TRADE_DATE"))
                .andExpect(jsonPath("$.data.tables[3].strategyLabel").value("按交易日"))
                .andExpect(jsonPath("$.data.tables[3].rowCount").value(3))
                .andExpect(jsonPath("$.data.tables[3].rowCountApprox").value(false))
                .andExpect(jsonPath("$.data.tables[3].latestDate").value("20261124"))
                .andExpect(jsonPath("$.data.tables[3].latestDateColumn").value("trade_date"))
                .andExpect(jsonPath("$.data.tables[3].lagDays").value(0))
                .andExpect(jsonPath("$.data.tables[3].lastJobStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.tables[3].lastJobType").value("INCR"))
                .andExpect(jsonPath("$.data.tables[3].dailyEnabled").value(true))
                .andExpect(jsonPath("$.data.tables[3].health").value("ok"))
                .andExpect(jsonPath("$.data.tables[3].rateLimit").value(500));
    }

    @Test
    void tableDetailReturnsPkRecentLoadAndColumns() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.table").value("daily"))
                .andExpect(jsonPath("$.data.pk").value("(ts_code, trade_date)"))
                .andExpect(jsonPath("$.data.rowCount").value(3))
                .andExpect(jsonPath("$.data.rowCountApprox").value(false))
                .andExpect(jsonPath("$.data.latestDate").value("20261124"))
                .andExpect(jsonPath("$.data.dailyEnabled").value(true))
                .andExpect(jsonPath("$.data.lastFullBackfill").value(nullValue()))
                .andExpect(jsonPath("$.data.recentDailyLoad.length()").value(1))
                .andExpect(jsonPath("$.data.recentDailyLoad[0].date").value("20261124"))
                .andExpect(jsonPath("$.data.recentDailyLoad[0].rows").value(3))
                .andExpect(jsonPath("$.data.columns.length()").value(11))
                .andExpect(jsonPath("$.data.columns[0].name").value("ts_code"))
                .andExpect(jsonPath("$.data.columns[1].name").value("trade_date"));
    }

    @Test
    void tableDetailIncludesLastFullBackfillWhenPresent() throws Exception {
        OpsTestSupport.insertJob(jdbc, "daily", "BACKFILL", "SUCCESS", "20200101~20261124",
                LocalDateTime.of(2026, 11, 20, 9, 0));

        mockMvc.perform(get("/api/ops/tables/daily"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.lastFullBackfill.jobType").value("BACKFILL"))
                .andExpect(jsonPath("$.data.lastFullBackfill.status").value("SUCCESS"));
    }

    @Test
    void unknownTableReturns404() throws Exception {
        mockMvc.perform(get("/api/ops/tables/not_a_table"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value(containsString("未知数据表")));
    }

    // ================================================================ 预览

    @Test
    void previewKeyModeReturnsKeyColumnsAndLimit() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.table").value("daily"))
                .andExpect(jsonPath("$.data.columnMode").value("key"))
                // 幂等键 2 列 + 前 8 个非键列
                .andExpect(jsonPath("$.data.columns.length()").value(10))
                .andExpect(jsonPath("$.data.columns[0]").value("ts_code"))
                .andExpect(jsonPath("$.data.columns[1]").value("trade_date"))
                .andExpect(jsonPath("$.data.columns[2]").value("open"))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.limited").value(true));
    }

    @Test
    void previewAllModeAndFilters() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("columnMode", "all"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.columns.length()").value(11));

        mockMvc.perform(get("/api/ops/tables/daily/preview")
                        .param("code", "000001.SZ").param("columnMode", "all"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].ts_code").value("000001.SZ"));

        mockMvc.perform(get("/api/ops/tables/daily/preview")
                        .param("start", "20261124").param("end", "20261124"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void previewRejectsDeepPagination() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("page", "5001").param("size", "20"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("预览分页超限")));
    }

    @Test
    void previewRejectsInvalidParams() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("columnMode", "weird"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("start", "2026-11-24"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("yyyyMMdd")));
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("size", "200"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/ops/tables/daily/preview").param("order", "random"))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================ 作业时间线

    @Test
    void tableJobsReturnsPagedHistory() throws Exception {
        OpsTestSupport.insertJob(jdbc, "daily", "BACKFILL", "SUCCESS", "20200101~20261124",
                LocalDateTime.of(2026, 11, 20, 9, 0));

        mockMvc.perform(get("/api/ops/tables/daily/jobs").param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].jobType").value("BACKFILL"));
    }

    @Test
    void tableJobsRejectsUnknownTableAndBadPage() throws Exception {
        mockMvc.perform(get("/api/ops/tables/unknown_table/jobs"))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/ops/tables/daily/jobs").param("size", "101"))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================ 动作

    @Test
    void runTableTriggersIncrThroughFacade() throws Exception {
        mockMvc.perform(post("/api/ops/tables/daily/run"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.runId").value("M20261124-02"));

        assertThat(facade.lastTable).isEqualTo("daily");
        assertThat(facade.lastJobType).isEqualTo("INCR");
        assertThat(facade.lastStart).isNull();
        assertThat(facade.lastEnd).isNull();
    }

    @Test
    void runTablePassesThroughConflictFromFacade() throws Exception {
        facade.singleTableFailure = ApiException.conflict("每日编排运行中，单表作业暂不可触发");

        mockMvc.perform(post("/api/ops/tables/daily/run"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value(containsString("编排运行中")));
    }

    @Test
    void backfillPassesRangeThroughFacade() throws Exception {
        mockMvc.perform(post("/api/ops/tables/daily/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"20200101\",\"end\":\"20261124\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true));

        assertThat(facade.lastTable).isEqualTo("daily");
        assertThat(facade.lastJobType).isEqualTo("BACKFILL");
        assertThat(facade.lastStart).isEqualTo("20200101");
        assertThat(facade.lastEnd).isEqualTo("20261124");
    }

    @Test
    void backfillAllowsEmptyRangeAndRejectsInvalidRange() throws Exception {
        mockMvc.perform(post("/api/ops/tables/daily/backfill")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(facade.lastStart).isNull();
        assertThat(facade.lastEnd).isNull();

        mockMvc.perform(post("/api/ops/tables/daily/backfill")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"start\":\"2026-01-01\"}"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/ops/tables/daily/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"20261231\",\"end\":\"20260101\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("start 不能晚于 end")));
    }

    @Test
    void dailyToggleWritesSysConfigAndReflectsHealth() throws Exception {
        mockMvc.perform(post("/api/ops/tables/daily/daily-toggle")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.table").value("daily"))
                .andExpect(jsonPath("$.data.dailyEnabled").value(false));

        String value = jdbc.queryForObject(
                "SELECT config_value FROM sys_config WHERE config_key = 'ingest.daily.daily'",
                String.class);
        String remark = jdbc.queryForObject(
                "SELECT remark FROM sys_config WHERE config_key = 'ingest.daily.daily'", String.class);
        assertThat(value).isEqualTo("false");
        assertThat(remark).contains("运维台手动切换每日增量");

        // 关闭后健康度应为 idle
        mockMvc.perform(get("/api/ops/tables"))
                .andExpect(jsonPath("$.data.tables[3].dailyEnabled").value(false))
                .andExpect(jsonPath("$.data.tables[3].health").value("idle"));

        // 重新打开 → 恢复默认（true）
        mockMvc.perform(post("/api/ops/tables/daily/daily-toggle")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.data.dailyEnabled").value(true));
    }

    @Test
    void dailyToggleRejectsMissingEnabledAndUnknownTable() throws Exception {
        mockMvc.perform(post("/api/ops/tables/daily/daily-toggle")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/ops/tables/not_a_table/daily-toggle")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(404));
    }
}
