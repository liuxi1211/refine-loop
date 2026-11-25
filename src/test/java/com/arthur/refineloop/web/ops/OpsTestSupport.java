package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.constant.CommonConstants;
import com.arthur.refineloop.tushare.ingest.dto.BackfillPlan;
import com.arthur.refineloop.tushare.ingest.service.IngestFacade;
import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.arthur.refineloop.web.GlobalExceptionHandler;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Step6 运维 API 控制器测试基座（standalone MockMvc，不启动 Spring 上下文）。
 *
 * <p>注入与生产一致的 Jackson 配置（JavaTimeModule + 关闭时间戳序列化），
 * 使 {@code LocalDateTime} 断言为 ISO 字符串；IngestFacade 用测试桩，IngestLogQueryService 用 SQLite 真实现。
 *
 * <p><b>为什么还要配 {@link RequestMappingHandlerMapping}</b>：{@code /api} 前缀在生产由
 * {@code WebMvcConfig#configurePathMatch} 统一挂载，而 standalone MockMvc 不走
 * {@code WebMvcConfigurer}——不补这一步，控制器会暴露在 {@code /ops/tables} 而测试打的是
 * {@code /api/ops/tables}，请求直接落到兜底异常变成 500。这里复制同一套前缀规则，
 * 好处是切片测试继续断言<b>对外真实 URL</b>，前缀一旦失效测试立刻红。
 */
final class OpsTestSupport {

    /** 固定业务日期（Asia/Shanghai）。 */
    static final String TRADE_DATE = "20261124";

    private OpsTestSupport() {
    }

    static MockMvc mockMvc(Object... controllers) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controllers)
                .setCustomHandlerMapping(OpsTestSupport::prefixedHandlerMapping)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    /** 复制 {@code WebMvcConfig} 的前缀规则：web 包下的控制器统一挂 {@code /api}。 */
    private static RequestMappingHandlerMapping prefixedHandlerMapping() {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setPathPrefixes(Map.of(CommonConstants.API_PREFIX,
                HandlerTypePredicate.forBasePackage("com.arthur.refineloop.web")));
        return mapping;
    }

    /** 种子：交易日历 + 3 只在市股票 + 行情/财务数据 + 5 张关键表当日 SUCCESS 作业。 */
    static void seedTradingDay(JdbcTemplate jdbc, LocalDateTime jobTime) {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')", TRADE_DATE);
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
        for (String table : List.of("daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal")) {
            insertJob(jdbc, table, "INCR", "SUCCESS", TRADE_DATE, jobTime);
        }
    }

    static void insertJob(JdbcTemplate jdbc, String table, String jobType, String status,
                          String rangeParams, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO ingest_log"
                        + " (run_id, table_name, job_type, range_params, status, created_at)"
                        + " VALUES ('R-" + table + "-" + status + "', ?, ?, ?, ?, ?)",
                table, jobType, rangeParams, status, Timestamp.valueOf(createdAt));
    }

    /** IngestFacade 测试桩：记录调用参数，可注入 409/404 等异常验证透传。 */
    static final class FakeIngestFacade implements IngestFacade {

        IngestTriggerAck dailyAck = new IngestTriggerAck(true, "R20261124-01", "每日增量编排已接受，正在异步执行");
        IngestTriggerAck singleAck = new IngestTriggerAck(true, "M20261124-02", "单表作业已接受，异步执行中");
        IngestTriggerAck retryAck = new IngestTriggerAck(true, "M20261124-03", "失败作业重试已接受");
        OrchestrationProgress progress = new OrchestrationProgress(false, "-", "IDLE", null, null,
                "NONE", List.of(), 0, 0, 0, 0, 0, 0);
        BackfillPlan plan = new BackfillPlan("BF-20261124-01", "standard", List.of("daily"),
                List.of(new BackfillPlan.PlanBatch(1, List.of("daily"))),
                "回填计划已生成（未自动执行）");

        RuntimeException singleTableFailure;
        RuntimeException retryFailure;

        int dailyCalls;
        Long retriedJobId;
        String lastTable;
        String lastJobType;
        String lastStart;
        String lastEnd;
        String lastMode;
        List<String> lastTables;

        @Override
        public IngestTriggerAck triggerDaily() {
            dailyCalls++;
            return dailyAck;
        }

        @Override
        public IngestTriggerAck triggerSingleTable(String table, String jobType, String start, String end) {
            if (singleTableFailure != null) {
                throw singleTableFailure;
            }
            lastTable = table;
            lastJobType = jobType;
            lastStart = start;
            lastEnd = end;
            return singleAck;
        }

        @Override
        public IngestTriggerAck retryJob(long ingestLogId) {
            if (retryFailure != null) {
                throw retryFailure;
            }
            retriedJobId = ingestLogId;
            return retryAck;
        }

        @Override
        public OrchestrationProgress currentOrchestration() {
            return progress;
        }

        @Override
        public BackfillPlan createBackfillPlan(String mode, List<String> tables) {
            lastMode = mode;
            lastTables = tables;
            return plan;
        }
    }
}
