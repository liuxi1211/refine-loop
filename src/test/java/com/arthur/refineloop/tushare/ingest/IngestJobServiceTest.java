package com.arthur.refineloop.tushare.ingest;

import com.arthur.refineloop.config.IngestProperties;
import com.arthur.refineloop.config.RateLimitProperties;
import com.arthur.refineloop.config.TushareProperties;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.support.TushareMockResponses;
import com.arthur.refineloop.tushare.client.TushareClient;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobResult;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.tushare.ingest.service.IngestJobService;
import com.arthur.refineloop.tushare.ingest.service.IngestLogService;
import com.arthur.refineloop.tushare.ingest.service.IngestTableWriter;
import com.arthur.refineloop.tushare.ratelimit.RateLimitCounterRecorder;
import com.arthur.refineloop.tushare.ratelimit.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;

/**
 * T4 验收（无库环境替代执行）：SQLite + MockRestServiceServer 验证五种策略、
 * 幂等重跑、ingest_log 记录、特殊分页规则。
 */
class IngestJobServiceTest {

    private static final String URL = "http://api.tushare.pro/";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private JdbcTemplate jdbc;
    private IngestJobService jobService;
    private IngestLogService logService;
    private IngestTableWriter writer;
    private TushareProperties tushareProperties;
    private RateLimiterRegistry limiter;
    private MockRestServiceServer server;
    private MutableClock clock;
    private IngestProperties props;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        SchemaTestSupport.reset(jdbc);
        clock = MutableClock.at("2026-11-24T10:00:00Z");
        props = new IngestProperties();
        props.setPerStockConcurrency(1);
        props.setJobRetryCount(3);
        props.setJobRetryBackoffMillis(0L);

        tushareProperties = new TushareProperties();
        tushareProperties.setBaseUrl("http://api.tushare.pro");
        tushareProperties.setToken("test-token");
        tushareProperties.setRetryBackoffMillis(0L);

        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        rateLimitProperties.setDefaultLimit(100_000);
        rateLimitProperties.setSmoothingEnabled(false);
        rateLimitProperties.setCounterEnabled(false);
        limiter = new RateLimiterRegistry(rateLimitProperties, clock, millis -> {
        }, RateLimitCounterRecorder.NOOP);

        writer = new IngestTableWriter(SchemaTestSupport.batchExecutor(jdbc));
        logService = new IngestLogService(SchemaTestSupport.mapper(jdbc, IngestLogMapper.class), clock);
        rebindClient();
    }

    /** 重建 mock server + client + jobService（MockRestServiceServer 不允许在已有真实请求后再加期望）。 */
    private void rebindClient() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TushareClient client = new TushareClient(builder, tushareProperties, limiter);
        jobService = new IngestJobService(client, writer, logService,
                SchemaTestSupport.mapper(jdbc, TushareTableMapper.class),
                SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class), clock, props);
    }

    // ------------------------------------------------------------------ helpers

    private TableDef def(String table) {
        TableDef def = TushareTableRegistry.byTable(table);
        assertThat(def).as("注册表应包含表 " + table).isNotNull();
        return def;
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private Map<String, Object> latestLog(String table) {
        return jdbc.queryForMap("SELECT * FROM ingest_log WHERE table_name = ? ORDER BY id DESC LIMIT 1", table);
    }

    private void seedTradeCal(String... openDates) {
        for (String date : openDates) {
            jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open, pretrade_date) VALUES ('SSE', ?, '1', ?)",
                    date, date);
        }
    }

    private void seedStockBasic(String... codes) {
        for (String code : codes) {
            jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES (?, ?, 'L')", code, "测试" + code);
        }
    }

    private void seedL1Industry(String... codes) {
        for (String code : codes) {
            jdbc.update("INSERT INTO sw_industry (index_code, industry_name, level, src) VALUES (?, ?, 'L1', 'SW2021')",
                    code, "行业" + code);
        }
    }

    private static String dailyPage(int rows, String tradeDate, int startIndex) {
        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";
        return TushareMockResponses.ok(fields, TushareMockResponses.repeatedRows(rows, i -> List.of(
                String.format("%06d.SZ", startIndex + i), tradeDate, 10.0, 11.0, 9.5, 10.5, 10.0, 0.5, 5.0,
                1000.0, 2000.0)));
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void daily按交易日分页拉取且同一作业重跑不翻倍() {
        seedTradeCal("20260105");
        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";
        // 第一次：两页（10000 + 100）；重跑：同样的两页（upsert 幂等）
        for (int run = 0; run < 2; run++) {
            server.expect(requestTo(URL))
                    .andExpect(content().string(containsString("\"offset\":\"0\"")))
                    .andRespond(withSuccess(dailyPage(10_000, "20260105", 0), JSON));
            server.expect(requestTo(URL))
                    .andExpect(content().string(containsString("\"offset\":\"10000\"")))
                    .andRespond(withSuccess(dailyPage(100, "20260105", 10_000), JSON));
        }

        IngestJobResult first = jobService.runJob(def("daily"), IngestJobService.JOB_INCR, "20260105", "20260105",
                "RUN-1");
        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(first.rowsFetched()).isEqualTo(10_100);
        assertThat(first.rowsWritten()).isEqualTo(10_100);
        assertThat(count("daily")).isEqualTo(10_100);
        Map<String, Object> logRow = latestLog("daily");
        assertThat(logRow.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) logRow.get("rows_fetched")).longValue()).isEqualTo(10_100);
        assertThat(logRow.get("start_time")).isNotNull();
        assertThat(logRow.get("end_time")).isNotNull();

        IngestJobResult second = jobService.runJob(def("daily"), IngestJobService.JOB_INCR, "20260105",
                "20260105", "RUN-2");
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(count("daily")).as("同一作业重跑不应产生重复数据").isEqualTo(10_100);
        server.verify();
    }

    @Test
    void stockBasic三态全量替换() {
        String fields = "ts_code,symbol,name,area,industry,fullname,enname,cnspell,market,exchange,curr_type,"
                + "list_status,list_date,delist_date,is_hs,act_name,act_ent_type";
        // 第一次全量：L 2 行、D 1 行、P 1 行
        server.expect(requestTo(URL)).andExpect(content().string(containsString("\"list_status\":\"L\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"000001.SZ", "1", "平安银行", null, null, null, null, null, null, null, null, "L",
                                "19910403", null, null, null, null},
                        new Object[]{"000002.SZ", "2", "万科A", null, null, null, null, null, null, null, null, "L",
                                "19910129", null, null, null, null})), JSON));
        server.expect(requestTo(URL)).andExpect(content().string(containsString("\"list_status\":\"D\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"000003.SZ", "3", "退市股", null, null, null, null, null, null, null, null, "D",
                                "19910101", "20200101", null, null, null})), JSON));
        server.expect(requestTo(URL)).andExpect(content().string(containsString("\"list_status\":\"P\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"000004.SZ", "4", "暂停股", null, null, null, null, null, null, null, null, "P",
                                "19910101", null, null, null, null})), JSON));

        IngestJobResult result = jobService.runJob(def("stock_basic"), IngestJobService.JOB_FULL, null, null, "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(count("stock_basic")).isEqualTo(4);
        server.verify();

        // 第二次全量：只剩 1 只（全量替换语义，旧行必须消失）；需要新的 mock server
        rebindClient();
        for (String status : List.of("L", "D", "P")) {
            int rows = "L".equals(status) ? 1 : 0;
            List<List<Object>> items = rows == 0 ? List.of() : TushareMockResponses.rows(
                    new Object[]{"000005.SZ", "5", "新上市", null, null, null, null, null, null, null, null, "L",
                            "20240101", null, null, null, null});
            server.expect(requestTo(URL)).andExpect(content().string(containsString("\"list_status\":\"" + status + "\"")))
                    .andRespond(withSuccess(TushareMockResponses.ok(fields, items), JSON));
        }
        IngestJobResult replaced = jobService.runJob(def("stock_basic"), IngestJobService.JOB_FULL, null, null,
                "RUN-2");
        assertThat(replaced.status()).isEqualTo("SUCCESS");
        assertThat(count("stock_basic")).isEqualTo(1);
        server.verify();
    }

    @Test
    void finaIndicator每股拉取且重跑幂等() {
        List<String> codes = List.of("000001.SZ", "000002.SZ", "000003.SZ");
        seedStockBasic(codes.toArray(new String[0]));
        String fields = "ts_code,ann_date,end_date,roe,roa,grossprofit_margin,netprofit_margin,"
                + "dt_netprofit_yoy,or_yoy,debt_to_assets,dt_eps_yoy";
        for (int run = 0; run < 2; run++) {
            for (String code : codes) {
                server.expect(requestTo(URL))
                        .andExpect(content().string(containsString("\"ts_code\":\"" + code + "\"")))
                        .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                                new Object[]{code, "20260430", "20260331", 12.5, 5.1, 30.2, 10.1, 8.8, 7.7, 45.0, 6.6},
                                new Object[]{code, "20250830", "20250630", 11.5, 4.9, 29.9, 9.9, 8.1, 7.1, 44.0, 6.1})),
                                JSON));
            }
        }
        IngestJobResult first = jobService.runJob(def("fina_indicator"), IngestJobService.JOB_INCR, "20200101",
                "20261231", "RUN-1");
        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(count("fina_indicator")).isEqualTo(6);
        // 主键无重复
        Long duplicates = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT ts_code, end_date FROM fina_indicator GROUP BY ts_code, end_date
                HAVING COUNT(*) > 1) t
                """, Long.class);
        assertThat(duplicates).isZero();

        IngestJobResult second = jobService.runJob(def("fina_indicator"), IngestJobService.JOB_INCR, "20200101",
                "20261231", "RUN-2");
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(count("fina_indicator")).isEqualTo(6);
        server.verify();
    }

    @Test
    void finaMainbz改为perStock全历史不带日期参数() {
        seedStockBasic("600519.SH");
        String fields = "ts_code,end_date,bz_item,bz_code,bz_sales,bz_profit,bz_cost,curr_type,update_flag";
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"ts_code\":\"600519.SH\"")))
                .andExpect(content().string(not(containsString("start_date"))))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"600519.SH", "20251231", "茅台酒", "P01", 100.0, 50.0, 10.0, "CNY", "1"},
                        new Object[]{"600519.SH", "20251231", "其他", "P02", 5.0, 1.0, 2.0, "CNY", "1"},
                        new Object[]{"600519.SH", "20250630", "茅台酒", "P01", 60.0, 30.0, 6.0, "CNY", "1"})), JSON));

        IngestJobResult result = jobService.runJob(def("fina_mainbz"), IngestJobService.JOB_INCR, "20200101",
                "20261231", "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(count("fina_mainbz")).isEqualTo(3);
        server.verify();
    }

    @Test
    void indexMemberAll按L1代码循环且全量替换() {
        seedL1Industry("801010.SI", "801030.SI");
        String fields = "l1_code,l1_name,l2_code,l2_name,l3_code,l3_name,ts_code,name,in_date,out_date,is_new";
        server.expect(requestTo(URL)).andExpect(content().string(containsString("801010.SI")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"801010.SI", "农林牧渔", "801011.SI", "种植业", "801011.SI", "种植业",
                                "000001.SZ", "平安银行", "20200101", null, "Y"},
                        new Object[]{"801010.SI", "农林牧渔", "801012.SI", "渔业", "801012.SI", "渔业",
                                "000002.SZ", "万科A", "20200101", null, "Y"})), JSON));
        server.expect(requestTo(URL)).andExpect(content().string(containsString("801030.SI")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                        new Object[]{"801030.SI", "基础化工", "801031.SI", "化学原料", "801031.SI", "化学原料",
                                "000003.SZ", "国农科技", "20200101", null, "Y"})), JSON));

        IngestJobResult result = jobService.runJob(def("sw_industry_member"), IngestJobService.JOB_FULL, null, null,
                "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(count("sw_industry_member")).isEqualTo(3);
        server.verify();
    }

    @Test
    void pledgeStat固定1000每页() {
        String fields = "ts_code,end_date,pledge_count,unrest_pledge,rest_pledge,total_share,pledge_ratio";
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"limit\":\"1000\"")))
                .andExpect(content().string(containsString("\"offset\":\"0\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(1000, i -> List.of(
                                String.format("%06d.SZ", i), "20260102", 5, 100.0, 200.0, 1000.0, 20.0))), JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"limit\":\"1000\"")))
                .andExpect(content().string(containsString("\"offset\":\"1000\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(500, i -> List.of(
                                String.format("9%05d.SZ", i), "20260102", 5, 100.0, 200.0, 1000.0, 20.0))), JSON));

        IngestJobResult result = jobService.runJob(def("pledge_stat"), IngestJobService.JOB_FULL,
                "20260102", "20260102", "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.rowsFetched()).isEqualTo(1500);
        assertThat(count("pledge_stat")).isEqualTo(1500);
        server.verify();
    }

    @Test
    void repurchase固定2000每页且整公告删插() {
        String fields = "ts_code,ann_date,end_date,proc,exp_date,vol,amount,high_limit,low_limit";
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"limit\":\"2000\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(2000, i -> List.of(
                                "000001.SZ", "20260110", "20251231", "完成", "20260109", 100.0, 1000.0, 12.0, 8.0))),
                        JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"offset\":\"2000\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(300, i -> List.of(
                                "000002.SZ", "20260111", "20251231", "完成", "20260110", 200.0, 2000.0, 22.0, 18.0))),
                        JSON));

        IngestJobResult result = jobService.runJob(def("repurchase"), IngestJobService.JOB_FULL, "20260101",
                "20260131", "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.rowsFetched()).isEqualTo(2300);
        assertThat(count("repurchase")).isEqualTo(2300);
        server.verify();
    }

    @Test
    void reportRc只拉单页且不翻页() {
        String fields = "ts_code,name,report_date,report_title,report_type,classify,org_name,author_name,quarter,"
                + "op_rt,op_pr,tp,np,eps,pe,rd,roe,ev_ebitda,rating,max_price,min_price,imp_dg,create_time";
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"limit\":\"3000\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(3000, i -> List.of(
                                "600519.SH", "贵州茅台", "20260105", "研报", "个股研报", "食品饮料", "某证券",
                                "分析师", "2026Q4", 100.0, 50.0, 2000.0, 500.0, 60.0, 30.0, 0.1, 20.0, 15.0,
                                "买入", 2000.0, 1500.0, "", "20260105"))), JSON));

        IngestJobResult result = jobService.runJob(def("report_rc"), IngestJobService.JOB_FULL, "20260801",
                "20261124", "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        // MAX_PAGES=1：即使首页是满页也不翻第二页
        assertThat(result.rowsFetched()).isEqualTo(3000);
        assertThat(count("report_rc")).isEqualTo(3000);
        server.verify();
    }

    @Test
    void suspendD单月offset超限降级perStock() {
        props.setSuspendOffsetLimit(5000);
        seedStockBasic("000001.SZ", "000002.SZ");
        String fields = "ts_code,trade_date,suspend_timing,suspend_type";
        // 首页满 5000 行 → offset 达到上限 → 降级 per-stock
        server.expect(requestTo(URL))
                .andExpect(content().string(containsString("\"limit\":\"5000\"")))
                .andRespond(withSuccess(TushareMockResponses.ok(fields,
                        TushareMockResponses.repeatedRows(5000, i -> List.of("000001.SZ", "20260105", "", "S"))), JSON));
        for (String code : List.of("000001.SZ", "000002.SZ")) {
            server.expect(requestTo(URL))
                    .andExpect(content().string(containsString("\"ts_code\":\"" + code + "\"")))
                    .andRespond(withSuccess(TushareMockResponses.ok(fields, TushareMockResponses.rows(
                            new Object[]{code, "20260105", "", "S"},
                            new Object[]{code, "20260106", "", "R"})), JSON));
        }

        IngestJobResult result = jobService.runJob(def("stock_suspend_d"), IngestJobService.JOB_FULL, "20260101",
                "20260131", "RUN-1");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(count("stock_suspend_d")).as("降级路径应写入 per-stock 数据").isEqualTo(4);
        server.verify();
    }

    @Test
    void 参数错误作业置FAILED并记录ingestLog() {
        seedTradeCal("20260105");
        server.expect(requestTo(URL))
                .andRespond(withSuccess(TushareMockResponses.error(50101, "必填参数缺失"), JSON));

        IngestJobResult result = jobService.runJob(def("daily"), IngestJobService.JOB_INCR, "20260105", "20260105",
                "RUN-FAIL");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("50101");
        Map<String, Object> logRow = latestLog("daily");
        assertThat(logRow.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(logRow.get("error_msg"))).contains("50101");
        server.verify();
    }

    @Test
    void 每日增量编排排除停用的hkHold() {
        List<String> enabled = new ArrayList<>();
        for (TableDef def : TushareTableRegistry.dailyEnabled()) {
            enabled.add(def.tableName());
        }
        assertThat(enabled).doesNotContain("hk_hold");
        assertThat(enabled).hasSize(40);
    }
}
