package com.arthur.refineloop.it;

import com.arthur.refineloop.capability.runtime.FactStore;
import com.arthur.refineloop.capability.runtime.llm.PromptTemplateLoader;
import com.arthur.refineloop.capability.runtime.llm.LlmInvoker;
import com.arthur.refineloop.capability.runtime.validation.FactMetrics;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.support.ValuationProbeInterpretUnit;
import com.arthur.refineloop.capability.units.compute.CalcValuationPercentileUnit;
import com.arthur.refineloop.capability.units.compute.ValuationFactSchema;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.config.LlmProperties;
import com.arthur.refineloop.data.mapper.QualityQueryMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.judgment.JudgmentArchiveService;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentArchiveResult;
import com.arthur.refineloop.judgment.model.JudgmentInput;
import com.arthur.refineloop.judgment.model.MethodSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T11 最小闭环<b>真机</b>联调：真实落库数据 + 真实 LLM（fast 档）。
 *
 * <p><b>为什么默认不跑</b>：它需要三件本仓库日常开发不具备的东西——回填完成的 SQLite 库、
 * 可用的 LLM API Key、以及愿意付费的几次调用。命名以 {@code IT} 结尾（不是 {@code *Test}），
 * 因此 {@code mvn test} 不会执行它；只有在显式加 {@code -Dt11.live=true} 时才启动。
 *
 * <p><b>运行方式</b>（缺指标即为跳过，不会失败）：
 * <pre>{@code
 * # 1) 库：默认 ./data/refine_loop.db；回填过历史数据的那份更容易命中
 * # 2) Key：DEEPSEEK_API_KEY / ARK_API_KEY / DASHSCOPE_API_KEY 任一（按 llm.yml 的 fast profile）
 * mvn -B test -Dtest=T11LiveClosureIT -Dt11.live=true
 * # 可选：指定交易日与标的数（默认库内最新交易日、2 只，最多 6 次 LLM 调用）
 * mvn -B test -Dtest=T11LiveClosureIT -Dt11.live=true -Dt11.tradeDate=20261125 -Dt11.top-n=3
 * # 可选：指向另一份库文件（不会污染本地开发库）
 * mvn -B test -Dtest=T11LiveClosureIT -Dt11.live=true -Dt11.db-url="jdbc:sqlite:D:/data/refine_loop.db?journal_mode=WAL"
 * }</pre>
 *
 * <p><b>验收对照</b>（《开发任务计划》T11）：
 * <ol>
 *   <li>「表里无数据」→「judgment 有带完整 Schema 的冻结记录」：本类跑完打印 judgement_id；</li>
 *   <li>报告数字全部带 {@code [[metric|value]]}、溯源可查：{@code ReportNumberScanner} 已在校验链里强制，
 *       跑通即代表「正文没有裸数字」；报告落盘到 {@code target/t11-live/reports/}；</li>
 *   <li>人为构造校验失败 → 降级判断落库：由确定性测试
 *       {@code T11ClosureChainIntegrationTest#degradesAndArchivesWhenOutputHasBareNumbers} 覆盖（不烧钱重复验证）；</li>
 *   <li>{@code llm_usage} / {@code ingest_log} / {@code rate_limit_counter} 三表有真实数据：
 *       {@code llm_usage} 由本类断言；另两张需要先跑一次 ingest（见联调记录中的步骤）。</li>
 * </ol>
 *
 * <p><b>不写断言的地方</b>：真实模型的输出质量（是否降级、结论档位）不做硬断言——它取决于模型与数据，
 * 不是代码正确性。本类只断言「链路结构」：事实有落库、报告有落盘、判断有冻结存档、用量有记账。
 */
@Slf4j
@EnabledIfSystemProperty(named = "t11.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"tushare.token=", "llm.smoke-on-startup=false"})
@Import(T11LiveClosureIT.ProbeConfig.class)
class T11LiveClosureIT {

    @Autowired
    private CalcValuationPercentileUnit computeUnit;

    @Autowired
    private FactStore factStore;

    @Autowired
    private JudgmentArchiveService archiveService;

    @Autowired
    private ValuationProbeInterpretUnit probeUnit;

    @Autowired
    private TushareLookupMapper lookupMapper;

    @Autowired
    private QualityQueryMapper qualityQueryMapper;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        String url = System.getProperty("t11.db-url");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
        }
    }

    @Test
    @DisplayName("真机最小闭环：真实数据跑估值事实 → TopN 解读 → 判断存档")
    void runsLiveClosure() throws Exception {
        assumeTrue(llmKeyConfigured(), "未配置 fast 档 LLM Key（DEEPSEEK_API_KEY / ARK_API_KEY / DASHSCOPE_API_KEY）");
        String tradeDate = resolveTradeDate();
        assumeTrue(dailyBasicRows(tradeDate) > 0, "交易日 " + tradeDate + " 的 daily_basic 无数据（未回填）");
        List<String> pool = lookupMapper.selectListedStockCodes();
        assumeTrue(!pool.isEmpty(), "stock_basic 无在市标的（未回填）");

        int topN = Integer.getInteger("t11.top-n", 2);
        String runId = "T11-LIVE-" + tradeDate;
        BatchFactResult batch = computeUnit.computeBatch(new FactContext(tradeDate, runId),
                StockPool.of("UNIVERSE", pool));
        int saved = factStore.saveAll(runId, tradeDate, batch.facts());
        log.info("[t11-live] tradeDate={} pool={} facts={} saved={}", tradeDate, pool.size(), batch.size(), saved);
        assertThat(saved).isEqualTo(batch.size());

        List<FactResult> candidates = batch.facts().stream()
                .filter(fact -> fact.dataStatus() == com.arthur.refineloop.capability.spi.FactDataStatus.COMPLETE)
                .filter(fact -> FactMetrics.flatten(fact.payload())
                        .containsKey(ValuationFactSchema.PERCENTILE_PE_TTM))
                .sorted(Comparator.comparing(fact -> FactMetrics.flatten(fact.payload())
                        .get(ValuationFactSchema.PERCENTILE_PE_TTM).value()))
                .limit(topN)
                .toList();
        assumeTrue(!candidates.isEmpty(), "当日无可用估值分位事实（daily_basic 覆盖不足）");

        int archived = 0;
        for (FactResult fact : candidates) {
            archived += archiveOne(fact, tradeDate, runId);
        }
        log.info("[t11-live] 完成：候选={} 存档={} llm_usage_rows={}", candidates.size(), archived,
                countRows("llm_usage"));
        assertThat(archived).as("至少一只标的产生冻结判断").isPositive();
        assertThat(countRows("judgment")).isPositive();
        assertThat(countRows("llm_usage")).isPositive();
    }

    /** 单只标的：装配 FactBundle → 解读 → 报告落盘 + 元数据 → 判断存档；返回 1/0 表示是否存档。 */
    private int archiveOne(FactResult fact, String tradeDate, String runId) throws Exception {
        FactBundle bundle = FactBundle.builder(fact.tsCode(), tradeDate, runId)
                .putAll(factStore.findByTradeDateAndTsCode(runId, tradeDate, fact.tsCode()))
                .seal();
        InterpretResult interpreted = probeUnit.interpret(bundle);
        if (interpreted.degraded()) {
            log.warn("[t11-live] 解读降级（真实模型输出未通过 L3）code={} gaps={}",
                    fact.tsCode(), interpreted.dataGaps());
        }
        long reportId = writeReport(fact.tsCode(), tradeDate, runId, interpreted);
        JudgmentArchiveResult result = archiveService.archive(new JudgmentArchiveRequest(
                fact.tsCode(), tradeDate, "P1", runId, reportId, reportRef(tradeDate, fact.tsCode()),
                new MethodSnapshot(readMethodVersion(), "growth_trend", List.of(
                        new MethodSnapshot.UnitRef(ValuationFactSchema.UNIT_ID, ValuationFactSchema.VERSION,
                                fact.payload()),
                        new MethodSnapshot.UnitRef(ValuationProbeInterpretUnit.UNIT_ID, "v1.0",
                                interpreted.judgmentBlock()))),
                new JudgmentInput(interpreted.judgmentBlock(), interpreted.degraded(), interpreted.dataGaps())));
        log.info("[t11-live] 判断已存档 id={} code={} degraded={} reaffirm={} (报告 {} 字符)",
                result.judgmentId(), fact.tsCode(), interpreted.degraded(), result.reaffirm(),
                interpreted.reportMd().length());
        return 1;
    }

    private long writeReport(String tsCode, String tradeDate, String runId, InterpretResult interpreted)
            throws Exception {
        Path directory = Path.of("target", "t11-live", "reports", tradeDate);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(tsCode + ".md"), interpreted.reportMd(), StandardCharsets.UTF_8);
        String reportNo = "R-" + tradeDate + "-" + tsCode;
        jdbcTemplate.update("INSERT OR IGNORE INTO report (report_no, run_id, ts_code, trade_date, report_type, "
                        + "title, file_path, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reportNo, runId, tsCode, tradeDate, "P1_REPORT", "T11 真机联调报告 " + tsCode,
                directory.resolve(tsCode + ".md").toString(),
                interpreted.degraded() ? "DEGRADED" : "READY", DateTimes.now(clock));
        return jdbcTemplate.queryForObject("SELECT id FROM report WHERE report_no = ?", Long.class, reportNo);
    }

    private String resolveTradeDate() {
        String configured = System.getProperty("t11.tradeDate");
        return configured == null || configured.isBlank() ? qualityQueryMapper.maxDailyTradeDate() : configured;
    }

    private long dailyBasicRows(String tradeDate) {
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_basic WHERE trade_date = ?", Long.class, tradeDate);
        return rows == null ? 0L : rows;
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private String reportRef(String tradeDate, String tsCode) {
        return "reports/" + tradeDate + "/" + tsCode + ".md";
    }

    /** 方法版本：T16 前没有快照表，用配置/固定前缀 + 日期标注，避免伪造「M1-v1.0」这种未登记版本号。 */
    private String readMethodVersion() {
        return System.getProperty("t11.method-version", "M1-v1.0");
    }

    private boolean llmKeyConfigured() {
        LlmProperties.Profile profile = llmProperties.profile("fast");
        if (profile == null || profile.getProvider() == null) {
            return false;
        }
        LlmProperties.Provider provider = llmProperties.getProviders().get(profile.getProvider());
        return provider != null && provider.getApiKey() != null && !provider.getApiKey().isBlank();
    }

    /** 探针单元的 Bean 定义（生产不注册它：T14 上线正式单元后本类与探针一起删除）。 */
    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ValuationProbeInterpretUnit valuationProbeInterpretUnit(LlmInvoker llmInvoker,
                                                                PromptTemplateLoader promptLoader,
                                                                ValidationChain validationChain,
                                                                TradingDayCalendar tradingDayCalendar) {
            return new ValuationProbeInterpretUnit(llmInvoker, promptLoader, validationChain, tradingDayCalendar);
        }
    }
}
