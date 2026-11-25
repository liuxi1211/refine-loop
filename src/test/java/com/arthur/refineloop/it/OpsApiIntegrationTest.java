package com.arthur.refineloop.it;

import com.arthur.refineloop.support.AuthTestSupport;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step6 运维 API + T5 质量门的端到端集成冒烟（真实 Spring 上下文 + 真实 SQLite 库）。
 *
 * <p>与 standalone MockMvc 单测互补：本测验证「Spring 装配 + 真实 JdbcTemplate/注册表 +
 * Result 包装 + 全局异常处理 + 质量门落库 + 鉴权拦截器」整条链路；与生产完全同引擎（SQLite），不联网。
 *
 * <p>每个用例重建库并重新登录：鉴权开启，运维写端点只有 ADMIN 能触发。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-db/ops_it.db?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&foreign_keys=on&synchronous=NORMAL",
        "tushare.token="
})
@AutoConfigureMockMvc
class OpsApiIntegrationTest {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    private String today;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        SchemaTestSupport.reset(jdbc);
        today = LocalDate.now().format(BASIC);

        jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES ('000001.SZ', '平安银行', 'L')");
        jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES ('600000.SH', '浦发银行', 'L')");
        jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES ('000003.SZ', '退市股', 'D')");
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open, pretrade_date) VALUES ('SSE', ?, '1', ?)",
                today, today);

        for (String code : new String[]{"000001.SZ", "600000.SH"}) {
            jdbc.update("INSERT INTO daily (ts_code, trade_date, close, vol, amount) VALUES (?, ?, 10.5, 1000, 2000)",
                    code, today);
            jdbc.update("INSERT INTO adj_factor (ts_code, trade_date, adj_factor) VALUES (?, ?, 1.0)", code, today);
            jdbc.update("INSERT INTO daily_basic (ts_code, trade_date, pe) VALUES (?, ?, 12.0)", code, today);
        }
        jdbc.update("""
                INSERT INTO fina_indicator (ts_code, ann_date, end_date, roe) VALUES ('000001.SZ', ?, '20260930', 12.5)
                """, today);

        LocalDateTime now = LocalDateTime.now();
        String[] criticalTables = {"daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal"};
        for (String table : criticalTables) {
            jdbc.update("""
                    INSERT INTO ingest_log (run_id, table_name, job_type, range_params, status, rows_fetched,
                        rows_written, retry_count, start_time, end_time, duration_ms, created_at)
                    VALUES ('IT-RUN-1', ?, 'INCR', ?, 'SUCCESS', 2, 2, 0, ?, ?, 10, ?)
                    """, table, today + "~" + today, Timestamp.valueOf(now), Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
        }
        jdbc.update("""
                INSERT INTO rate_limit_counter (api_name, minute_slot, call_count) VALUES ('daily', ?, 3)
                """, today + "1000");

        AuthTestSupport.ensureAdmin(userService);
        token = AuthTestSupport.login(mockMvc);
    }

    @Test
    void 表总览与详情返回注册表元数据和真实行数() throws Exception {
        mockMvc.perform(get("/api/ops/tables").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tables", hasSize(41)))
                .andExpect(jsonPath("$.data.summary.total").value(41))
                .andExpect(jsonPath("$.data.tables[?(@.table=='daily')].rowCount").value(hasItem(2)))
                .andExpect(jsonPath("$.data.tables[?(@.table=='daily')].health").value(hasItem("ok")))
                .andExpect(jsonPath("$.data.tables[?(@.table=='daily')].group").value(hasItem("行情")))
                .andExpect(jsonPath("$.data.tables[?(@.table=='hk_hold')].dailyEnabled").value(hasItem(false)));

        mockMvc.perform(get("/api/ops/tables/daily").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.table").value("daily"))
                .andExpect(jsonPath("$.data.group").value("行情"))
                .andExpect(jsonPath("$.data.strategyLabel").value("按交易日"))
                .andExpect(jsonPath("$.data.rateLimit").value(500))
                .andExpect(jsonPath("$.data.recentDailyLoad", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.columns", hasSize(greaterThanOrEqualTo(11))));
    }

    @Test
    void 数据预览支持全列与分页并带LIMIT保护() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/preview").with(AuthTestSupport.bearer(token))
                        .param("columnMode", "all")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value(2));

        // 分页过深 → 400「预览分页超限」（LIMIT 保护）
        mockMvc.perform(get("/api/ops/tables/daily/preview").with(AuthTestSupport.bearer(token))
                        .param("page", "1000")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void 作业历史与每日增量开关留痕() throws Exception {
        mockMvc.perform(get("/api/ops/tables/daily/jobs").with(AuthTestSupport.bearer(token))
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/ops/tables/daily/daily-toggle").with(AuthTestSupport.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dailyEnabled").value(false));

        mockMvc.perform(get("/api/ops/tables").with(AuthTestSupport.bearer(token)))
                .andExpect(jsonPath("$.data.tables[?(@.table=='daily')].dailyEnabled").value(hasItem(false)));

        // 恢复开关，避免影响其它用例
        mockMvc.perform(post("/api/ops/tables/daily/daily-toggle").with(AuthTestSupport.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dailyEnabled").value(true));
    }

    @Test
    void 编排进度在无运行任务时返回分层计划() throws Exception {
        mockMvc.perform(get("/api/ops/ingest/orchestrations/current").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.layers", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value(41));
    }

    @Test
    void 质量门执行落库并可查最近结果与历史() throws Exception {
        mockMvc.perform(get("/api/ops/quality/latest").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/ops/quality/run").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value(org.hamcrest.Matchers.oneOf("PASS", "PASS_WITH_WARN")))
                .andExpect(jsonPath("$.data.items", hasSize(greaterThanOrEqualTo(4))));

        mockMvc.perform(get("/api/ops/quality/latest").with(AuthTestSupport.bearer(token)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value(org.hamcrest.Matchers.oneOf("PASS", "PASS_WITH_WARN")));

        mockMvc.perform(get("/api/ops/quality/history").with(AuthTestSupport.bearer(token)).param("days", "30"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void 今日统计汇总作业调用与表健康度() throws Exception {
        mockMvc.perform(get("/api/ops/stats/today").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobs.total").value(5))
                .andExpect(jsonPath("$.data.jobs.success").value(5))
                .andExpect(jsonPath("$.data.tushareCallsToday").value(3))
                .andExpect(jsonPath("$.data.tables.total").value(41));
    }
}
