package com.arthur.refineloop.it;

import com.arthur.refineloop.capability.runtime.FactStore;
import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.llm.LlmInvoker;
import com.arthur.refineloop.capability.runtime.llm.LlmUsageStore;
import com.arthur.refineloop.capability.runtime.llm.PromptTemplateLoader;
import com.arthur.refineloop.capability.runtime.validation.ClasspathTagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.runtime.validation.ValidationValidators;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.runtime.validation.validators.FactsUsageValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.JudgmentRuleValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.ReportNumberScanner;
import com.arthur.refineloop.capability.runtime.validation.validators.SchemaValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.TagVocabularyValidator;
import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.support.StubChatClientProvider;
import com.arthur.refineloop.capability.support.ValuationFixtures;
import com.arthur.refineloop.capability.support.ValuationProbeInterpretUnit;
import com.arthur.refineloop.capability.units.compute.CalcValuationPercentileUnit;
import com.arthur.refineloop.capability.units.compute.ValuationFactSchema;
import com.arthur.refineloop.capability.runtime.validation.FactMetrics;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.config.JudgmentProperties;
import com.arthur.refineloop.config.LlmProperties;
import com.arthur.refineloop.data.mapper.DailyBasicMapper;
import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import com.arthur.refineloop.data.mapper.LlmUsageMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.judgment.BenchmarkResolver;
import com.arthur.refineloop.judgment.JudgmentArchiveContextResolver;
import com.arthur.refineloop.judgment.JudgmentArchiveService;
import com.arthur.refineloop.judgment.JudgmentIdGenerator;
import com.arthur.refineloop.judgment.JudgmentPayloadAssembler;
import com.arthur.refineloop.judgment.JudgmentReferenceChecker;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentArchiveResult;
import com.arthur.refineloop.judgment.model.JudgmentInput;
import com.arthur.refineloop.judgment.model.MethodSnapshot;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.support.TradingDays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 最小闭环联调（确定性版）：一条数据从落库到判断存档的端到端验证。
 *
 * <p><b>链路</b>：造三年每日指标 → {@code calc_valuation_percentile}（真实 BATCH 单元）→ 落 {@code unit_fact}
 * → 装配 per-stock {@code FactBundle} → {@code interpret_valuation_probe}（真实 {@code AbstractInterpretUnit}
 * + 真实 L3 五重校验 + 真实 {@code LlmInvoker}，仅模型网关被替换）→ 写报告 + {@code report} 元数据
 * → {@code JudgmentArchiveService} 存档 → {@code judgment} / {@code judgment_unit_ref}。
 *
 * <p><b>本测试证明什么</b>：整条链路的<b>代码</b>是通的、口径是对的、溯源是闭合的（报告里每个数字都能
 * 沿 {@code facts_used} 追回 {@code unit_fact}）、降级路径能落库。
 *
 * <p><b>本测试不能证明什么</b>：真实模型的通过率、真实行情下的数值分布、Tushare 三张观测表的真实数据
 * ——那些需要真实 Key 与回填数据，见 {@code T11LiveClosureIT} 与 {@code docs/T9-T11实施说明与联调记录.md}。
 * 因此 T11 仍是一个**待人工确认**的检查点，本类只提供自动化证据。
 */
@DisplayName("T11 最小闭环：估值事实 → 解读 → L3 → 判断存档")
class T11ClosureChainIntegrationTest {

    private static final String TRADE_DATE = "20261126";
    private static final String LOW_CODE = "600001.SH";
    private static final String HIGH_CODE = "600000.SH";
    private static final String MID_CODE = "600002.SH";
    private static final String INDUSTRY = "801780.SI";
    private static final String RUN_ID = "run-t11";
    private static final String METHOD_VERSION = "M1-v1.0";
    private static final String SCHOOL = "growth_trend";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-26T11:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final Pattern MARKER = Pattern.compile("\\[\\[([^\\[\\]|]+)\\|([^\\[\\]|]+)]]");

    private JdbcTemplate jdbc;
    private StubChatClientProvider chatClient;
    private ExecutorService executor;
    private CalcValuationPercentileUnit computeUnit;
    private FactStore factStore;
    private JudgmentArchiveService archiveService;
    private ValuationProbeInterpretUnit probeUnit;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        seedData();
        chatClient = new StubChatClientProvider();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        computeUnit = new CalcValuationPercentileUnit(
                mapper(DailyBasicMapper.class), mapper(SwIndustryMemberMapper.class));
        factStore = new FactStore(mapper(UnitFactMapper.class),
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), CLOCK);
        probeUnit = new ValuationProbeInterpretUnit(
                llmInvoker(), new PromptTemplateLoader(), validationChain(), calendar());
        archiveService = archiveService();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("全链路：3 只标的算分位 → Top1 解读 → 报告溯源闭合 → 判断冻结存档 → llm_usage 有记账")
    void runsMinimalClosureWithTraceableReport() throws Exception {
        BatchFactResult batch = computeUnit.computeBatch(
                new FactContext(TRADE_DATE, RUN_ID),
                StockPool.of("T11", List.of(LOW_CODE, MID_CODE, HIGH_CODE)));
        assertThat(batch.size()).isEqualTo(3);
        assertThat(factStore.saveAll(RUN_ID, TRADE_DATE, batch.facts())).isEqualTo(3);
        assertThat(countRows("unit_fact")).isEqualTo(3);

        FactResult top = lowestPercentile(batch.facts());
        assertThat(top.tsCode()).as("最低分位的标的应排在首位").isEqualTo(LOW_CODE);

        FactBundle bundle = FactBundle.builder(top.tsCode(), TRADE_DATE, RUN_ID)
                .putAll(factStore.findByTradeDateAndTsCode(RUN_ID, TRADE_DATE, top.tsCode()))
                .seal();
        chatClient.enqueue(validOutput(top));

        InterpretResult interpreted = probeUnit.interpret(bundle);

        assertThat(interpreted.degraded()).as("降级原因=%s", interpreted.dataGaps()).isFalse();
        assertThat(interpreted.reportMd()).contains("## 结论").contains("偏低");
        assertThat(interpreted.judgmentBlock()).containsEntry("unit_id", ValuationProbeInterpretUnit.UNIT_ID)
                .containsEntry("stock_code", LOW_CODE);

        long reportId = writeReport(top.tsCode(), interpreted.reportMd(), "READY", "P1_REPORT");
        JudgmentArchiveResult archived = archiveService.archive(archiveRequest(
                top, interpreted, reportId, "reports/" + TRADE_DATE + "/" + top.tsCode() + ".md"));

        assertThat(archived.judgmentId()).isEqualTo("J-20261126-P1-0001");
        assertThat(countRows("judgment")).isEqualTo(1);
        assertThat(countRows("judgment_unit_ref")).isEqualTo(2);
        assertThat(countRows("llm_usage")).isEqualTo(1);

        assertJudgmentPayloadComplete(archived, top);
        assertReportNumbersTraceable(interpreted.reportMd(), archived);
        assertLlmUsageRecorded();
    }

    @Test
    @DisplayName("重跑：同组判断被取代、新编号生效，旧行 payload 不变")
    void rerunSupersedesAndKeepsOldPayloadFrozen() throws Exception {
        FactResult fact = computeUnit.computeBatch(new FactContext(TRADE_DATE, RUN_ID),
                StockPool.of("T11", List.of(LOW_CODE))).facts().get(0);
        factStore.saveAll(RUN_ID, TRADE_DATE, List.of(fact));
        chatClient.enqueue(validOutput(fact));
        InterpretResult interpreted = probeUnit.interpret(bundleOf(fact));
        long reportId = writeReport(fact.tsCode(), interpreted.reportMd(), "READY", "P1_REPORT");

        JudgmentArchiveResult first = archiveService.archive(
                archiveRequest(fact, interpreted, reportId, null));
        chatClient.enqueue(validOutput(fact));
        JudgmentArchiveResult second = archiveService.archive(
                archiveRequest(fact, probeUnit.interpret(bundleOf(fact)), reportId, null));

        assertThat(second.judgmentId()).isEqualTo("J-20261126-P1-0002");
        assertThat(second.supersededJudgmentId()).isEqualTo(first.judgmentId());
        assertThat(countRows("unit_fact WHERE trade_date = '" + TRADE_DATE + "'")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM judgment WHERE superseded = 0", Long.class)).isEqualTo(1L);

        String oldPayload = mapper(JudgmentMapper.class).selectByJudgmentId(first.judgmentId()).getPayload();
        assertThat(oldPayload).isEqualTo(JsonUtils.toJson(first.payload()));
    }

    @Test
    @DisplayName("人为构造校验失败：三次重试耗尽 → 机械降级判断落库，data_gaps 注明原因")
    void degradesAndArchivesWhenOutputHasBareNumbers() throws Exception {
        FactResult fact = computeUnit.computeBatch(new FactContext(TRADE_DATE, RUN_ID),
                StockPool.of("T11", List.of(HIGH_CODE))).facts().get(0);
        factStore.saveAll(RUN_ID, TRADE_DATE, List.of(fact));

        // 正文里「分位约 88.5%」没有包裹溯源标记 → ReportNumberScanner 判失败；连续三次都脏 → 降级
        String dirty = dirtyOutput(fact);
        chatClient.enqueue(dirty).enqueue(dirty).enqueue(dirty);

        InterpretResult degraded = probeUnit.interpret(bundleOf(fact));

        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.reportMd()).isEmpty();
        assertThat(degraded.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("L3 校验重试耗尽"));
        assertThat(chatClient.callCount()).isEqualTo(3);

        long reportId = writeReport(fact.tsCode(), "", "DEGRADED", "JUDGMENT_DEGRADED");
        JudgmentArchiveResult archived = archiveService.archive(
                new JudgmentArchiveRequest(fact.tsCode(), TRADE_DATE, "P1", RUN_ID, reportId, null,
                        methodSnapshot(fact, degraded), new JudgmentInput(
                                degraded.judgmentBlock(), true, degraded.dataGaps())));

        Map<String, Object> payload = JsonUtils.parseMap(
                mapper(JudgmentMapper.class).selectByJudgmentId(archived.judgmentId()).getPayload());
        assertThat(payload).containsEntry("degraded", true);
        assertThat(payload.get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(gap -> assertThat(String.valueOf(gap)).contains("L3 校验重试耗尽"));
        assertThat(payload.get("verification_rules")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(payload.get("thesis")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("current_side", "neutral");
    }

    // ================================================================ 造数与装配

    private void seedData() {
        List<String> codes = List.of(LOW_CODE, MID_CODE, HIGH_CODE);
        for (String code : codes) {
            ValuationFixtures.stock(jdbc, code, "标的" + code.substring(0, 6));
            ValuationFixtures.industryMember(jdbc, code, INDUSTRY, "20200101", null);
            jdbc.update("INSERT INTO daily (ts_code, trade_date, close) VALUES (?, ?, 12.4)", code, TRADE_DATE);
            jdbc.update("INSERT INTO adj_factor (ts_code, trade_date, adj_factor) VALUES (?, ?, 1.25)",
                    code, TRADE_DATE);
        }
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        List<String> history = dates.subList(0, 499);
        // 同一段历史（10.00~19.96），当日值不同 → 分位不同：LOW 最低、HIGH 最高
        for (String code : codes) {
            ValuationFixtures.series(jdbc, code, history, ValuationFixtures.linearPe("10", "0.02"),
                    "1.5", "1.1", "4.8", "1000000");
        }
        ValuationFixtures.dailyBasic(jdbc, LOW_CODE, TRADE_DATE, "10.02", "1.5", "1.1", "4.8", "1000000");
        ValuationFixtures.dailyBasic(jdbc, MID_CODE, TRADE_DATE, "15", "1.5", "1.1", "4.8", "1000000");
        ValuationFixtures.dailyBasic(jdbc, HIGH_CODE, TRADE_DATE, "19.9", "1.5", "1.1", "4.8", "1000000");
        jdbc.update("INSERT INTO index_daily (ts_code, trade_date, close) VALUES ('000300.SH', ?, 3500)",
                TRADE_DATE);
    }

    private FactResult lowestPercentile(List<FactResult> facts) {
        return facts.stream()
                .min(Comparator.comparing(fact -> {
                    FactMetrics.Metric metric =
                            FactMetrics.flatten(fact.payload()).get(ValuationFactSchema.PERCENTILE_PE_TTM);
                    return metric == null ? BigDecimal.valueOf(Long.MAX_VALUE) : metric.value();
                }))
                .orElseThrow();
    }

    private FactBundle bundleOf(FactResult fact) {
        return FactBundle.builder(fact.tsCode(), TRADE_DATE, RUN_ID)
                .putAll(factStore.findByTradeDateAndTsCode(RUN_ID, TRADE_DATE, fact.tsCode()))
                .seal();
    }

    private LlmInvoker llmInvoker() {
        LlmProperties properties = new LlmProperties();
        LlmProperties.Profile profile = new LlmProperties.Profile();
        profile.setProvider("stub");
        profile.setModel("stub-model");
        profile.setTimeoutSeconds(30);
        profile.setInputPricePer1m(BigDecimal.ZERO);
        profile.setOutputPricePer1m(BigDecimal.ZERO);
        properties.getProfiles().put("fast", profile);
        properties.getRetry().setMaxAttempts(2);
        properties.getRetry().setInitialBackoffMillis(1);
        properties.getRetry().setMultiplier(1.0);
        properties.getRetry().setRateLimitBackoffMillis(1);
        LlmUsageStore usageStore = new LlmUsageStore(mapper(LlmUsageMapper.class), CLOCK);
        return new LlmInvoker(chatClient, usageStore, properties, executor);
    }

    /** 真实五重校验链：顺序即架构 §4.2.5，任何一环被替换都会让本测试失去意义。 */
    private ValidationChain validationChain() {
        return new ValidationChain(new ValidationValidators(
                new SchemaValidator(),
                new FactsUsageValidator(),
                new TagVocabularyValidator(new ClasspathTagVocabulary()),
                new JudgmentRuleValidator(new JudgmentRuleSetLoader(), new JudgmentRuleRegistry(List.of())),
                new ReportNumberScanner()));
    }

    private TradingDayCalendar calendar() {
        return (tradeDate, tradingDays) -> "20261203";
    }

    private JudgmentArchiveService archiveService() {
        return new JudgmentArchiveService(
                mapper(JudgmentMapper.class),
                mapper(JudgmentUnitRefMapper.class),
                new JudgmentArchiveContextResolver(mapper(TushareLookupMapper.class),
                        mapper(UnitFactMapper.class)),
                new JudgmentIdGenerator(),
                new JudgmentPayloadAssembler((tradeDate, tradingDays) -> TradingDays.after(tradeDate, tradingDays)),
                new BenchmarkResolver(mapper(SwIndustryMemberMapper.class), new JudgmentProperties()),
                JudgmentReferenceChecker.none(),
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                CLOCK);
    }

    private JudgmentArchiveRequest archiveRequest(FactResult fact, InterpretResult interpreted,
                                                  long reportId, String reportRef) {
        return new JudgmentArchiveRequest(fact.tsCode(), TRADE_DATE, "P1", RUN_ID, reportId, reportRef,
                methodSnapshot(fact, interpreted),
                new JudgmentInput(interpreted.judgmentBlock(), interpreted.degraded(), interpreted.dataGaps()));
    }

    private MethodSnapshot methodSnapshot(FactResult fact, InterpretResult interpreted) {
        return new MethodSnapshot(METHOD_VERSION, SCHOOL, List.of(
                new MethodSnapshot.UnitRef(ValuationFactSchema.UNIT_ID, ValuationFactSchema.VERSION,
                        fact.payload()),
                new MethodSnapshot.UnitRef(ValuationProbeInterpretUnit.UNIT_ID, "v1.0",
                        interpreted.judgmentBlock())));
    }

    // ================================================================ 模型输出（桩）

    /** 合法输出：正文每个数字都带溯源标记，判断块为完整主判断形状。 */
    private String validOutput(FactResult fact) {
        Map<String, FactMetrics.Metric> metrics = FactMetrics.flatten(fact.payload());
        BigDecimal percentile = metrics.get(ValuationFactSchema.PERCENTILE_PE_TTM).value();
        BigDecimal lossRatio = metrics.get(ValuationFactSchema.LOSS_RATIO).value();
        BigDecimal sampleDays = metrics.get(ValuationFactSchema.SAMPLE_DAYS).value();

        String report = "## 结论\n"
                + "估值处于历史低位，判断为偏低（分位 [[valuation.percentile_3y.pe_ttm|"
                + percentile.toPlainString() + "]]）。\n\n"
                + "## 事实底座\n"
                + "- 三年分位 [[valuation.percentile_3y.pe_ttm|" + percentile.toPlainString() + "]]\n"
                + "- 亏损样本占比 [[valuation.loss_ratio_3y|" + lossRatio.toPlainString() + "]]\n"
                + "- 分位覆盖交易日 [[valuation.window.sample_days|" + sampleDays.toPlainString() + "]] 个\n\n"
                + "## 反面证据\n"
                + "低分位也可能来自盈利预期下修：若后续盈利继续下滑，分位会被动抬升，偏低结论失效。\n\n"
                + "## 跟踪要点\n"
                + "关注下期业绩预告方向与行业景气变化，出现盈利预期上修时重估。\n";

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("unit_id", ValuationProbeInterpretUnit.UNIT_ID);
        block.put("unit_version", "v1.0");
        block.put("stock_code", fact.tsCode());
        block.put("trade_date", TRADE_DATE);
        block.put("dimension", "valuation");
        block.put("conclusion", "偏低");
        block.put("confidence", "中");
        block.put("facts_used", List.of(
                factUsage(payloadPath(ValuationFactSchema.PERCENTILE_PE_TTM), percentile, "pct"),
                factUsage(payloadPath(ValuationFactSchema.LOSS_RATIO), lossRatio, "ratio"),
                factUsage(payloadPath(ValuationFactSchema.SAMPLE_DAYS), sampleDays, "交易日")));
        block.put("logic_tags", List.of("估值处于历史低位"));
        block.put("data_gaps", List.of("无"));
        block.put("direction", Map.of("type", "bullish", "horizon_days", 45,
                "target_note", "预期相对基准获取超额收益"));
        // Map.of 不接受 null 值，stop_loss 必须用可变 Map 显式放 null（「无合法止损位」是 Schema 允许的状态）
        Map<String, Object> keyLevels = new LinkedHashMap<>();
        keyLevels.put("support", List.of());
        keyLevels.put("resistance", List.of());
        keyLevels.put("stop_loss", null);
        keyLevels.put("invalidation_condition", "关键价位单元尚未接入，本判断不含价位条件");
        block.put("key_levels", keyLevels);
        block.put("thesis", Map.of("bull_core", "估值分位处于历史低位，向下空间有限",
                "bear_core", "低分位可能反映盈利预期下修", "current_side", "bull", "confidence", "中"));
        block.put("verification_rules", List.of(
                Map.of("rule_type", "direction_fail", "params", Map.of("expire_at", "2027-01-10"),
                        "on_hit", "to_verify"),
                Map.of("rule_type", "logic_invalid",
                        "params", Map.of("watch_tags", List.of("估值处于历史低位")), "on_hit", "to_verify")));
        return report + "\n```json\n" + JsonUtils.toJson(block) + "\n```\n";
    }

    /** 脏输出：正文有未包裹标记的数字（这是 L3 的最后一道防线要拦的东西）。 */
    private String dirtyOutput(FactResult fact) {
        String valid = validOutput(fact);
        int blockStart = valid.indexOf("\n```json");
        return "## 结论\n当前分位约 88.5%，处于历史高位区间，判断为偏高。\n" + valid.substring(blockStart);
    }

    private static Map<String, Object> factUsage(String metric, BigDecimal value, String unit) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("metric", metric);
        usage.put("value", value);
        usage.put("unit", unit);
        return usage;
    }

    /**
     * 事实载荷里的指标路径 → 喂给模型后的指标路径。
     *
     * <p>探针单元把整份估值事实挂在 {@code valuation} 键下（见 {@code ValuationProbeInterpretUnit}），
     * 因此模型看到的路径比 {@code unit_fact.payload} 里多一层前缀。两处口径不一致是这类链路测试
     * 最经典的坑：校验器只认「模型看到的那份载荷」，测试若照搬事实根路径就会一直被判「编造指标」。
     */
    private static String payloadPath(String factMetricPath) {
        return "valuation." + factMetricPath;
    }

    // ================================================================ 断言

    private void assertJudgmentPayloadComplete(JudgmentArchiveResult archived, FactResult fact) {
        Map<String, Object> payload = JsonUtils.parseMap(
                mapper(JudgmentMapper.class).selectByJudgmentId(archived.judgmentId()).getPayload());

        assertThat(payload).containsKeys("judgment_id", "stock_code", "stock_name", "pipeline_id",
                "created_at", "trade_date", "benchmark", "report_ref", "degraded", "method",
                "direction", "key_levels", "thesis", "verification_rules", "data_gaps");
        assertThat(payload).containsEntry("stock_code", fact.tsCode())
                .containsEntry("trade_date", TRADE_DATE)
                .containsEntry("pipeline_id", "P1");
        @SuppressWarnings("unchecked")
        Map<String, Object> benchmark = (Map<String, Object>) payload.get("benchmark");
        assertThat(benchmark).containsEntry("industry_code", INDUSTRY);
        @SuppressWarnings("unchecked")
        Map<String, Object> method = (Map<String, Object>) payload.get("method");
        assertThat(method.get("units_used")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);
    }

    /** 溯源闭环：报告里的每个 [[metric|value]] 都能在 unit_fact 里按同名指标查到同值。 */
    private void assertReportNumbersTraceable(String report, JudgmentArchiveResult archived) {
        List<Map<String, Object>> factsUsed = factsUsedOf(archived);
        List<String> markers = new ArrayList<>();
        Matcher matcher = MARKER.matcher(report);
        while (matcher.find()) {
            String metric = matcher.group(1).trim();
            BigDecimal declared = new BigDecimal(matcher.group(2).trim());
            markers.add(metric);

            Map<String, Object> usage = factsUsed.stream()
                    .filter(item -> metric.equals(item.get("metric")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("报告标记 " + metric + " 未出现在 facts_used"));
            assertThat(new BigDecimal(String.valueOf(usage.get("value"))))
                    .as("标记 %s 的数值必须与 facts_used 一致", metric)
                    .isEqualByComparingTo(declared);

            String factPath = metric.substring("valuation.".length());
            FactResult fact = mapper(UnitFactMapper.class)
                    .selectByTradeDateAndTsCode(RUN_ID, TRADE_DATE, archived.payload().get("stock_code").toString())
                    .stream()
                    .filter(row -> ValuationFactSchema.UNIT_ID.equals(row.getUnitId()))
                    .map(row -> new FactResult(row.getTsCode(), row.getUnitId(), row.getUnitVersion(),
                            row.getUnitVersion(),
                            com.arthur.refineloop.capability.spi.FactDataStatus.valueOf(row.getDataStatus()),
                            JsonUtils.parseMap(row.getPayload()), List.of()))
                    .findFirst()
                    .orElseThrow();
            FactMetrics.Metric metric0 = FactMetrics.flatten(fact.payload()).get(factPath);
            assertThat(metric0).as("标记 %s 必须能在 unit_fact 中找到同名指标 %s", metric, factPath).isNotNull();
            assertThat(metric0.value()).as("unit_fact 的值必须与报告一致").isEqualByComparingTo(declared);
        }
        assertThat(markers).as("报告必须至少引用一个可溯源数字").isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> factsUsedOf(JudgmentArchiveResult archived) {
        return mapper(JudgmentUnitRefMapper.class).selectByJudgmentId(archived.judgmentId()).stream()
                .filter(ref -> ValuationProbeInterpretUnit.UNIT_ID.equals(ref.getUnitId()))
                .findFirst()
                .map(ref -> (List<Object>) JsonUtils.parseMap(ref.getOutputSummary()).get("facts_used"))
                .map(items -> items.stream().map(item -> (Map<String, Object>) item).toList())
                .orElseThrow(() -> new AssertionError("缺少解读单元的 judgment_unit_ref"));
    }

    private void assertLlmUsageRecorded() {
        Map<String, Object> usage = jdbc.queryForMap(
                "SELECT model_profile, model, prompt_tokens, completion_tokens, total_tokens FROM llm_usage");
        assertThat(usage.get("model_profile")).isEqualTo("fast");
        assertThat(usage.get("model")).isEqualTo("stub-model");
        assertThat(((Number) usage.get("total_tokens")).intValue()).isPositive();
        assertThat(((Number) usage.get("prompt_tokens")).intValue()).isPositive();
    }

    // ================================================================ 报告与库

    private long writeReport(String tsCode, String content, String status, String reportType)
            throws Exception {
        Path directory = Path.of("target", "t11", "reports", TRADE_DATE);
        Files.createDirectories(directory);
        Path file = directory.resolve(tsCode + ".md");
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);

        String reportNo = "R-" + TRADE_DATE + "-" + tsCode;
        jdbc.update("INSERT INTO report (report_no, run_id, ts_code, trade_date, report_type, title, "
                        + "file_path, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reportNo, RUN_ID, tsCode, TRADE_DATE, reportType, "闭联调报告 " + tsCode,
                file.toString(), status, DateTimes.now(CLOCK));
        return jdbc.queryForObject("SELECT id FROM report WHERE report_no = ?", Long.class, reportNo);
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private <T> T mapper(Class<T> type) {
        return SchemaTestSupport.mapper(jdbc, type);
    }
}
