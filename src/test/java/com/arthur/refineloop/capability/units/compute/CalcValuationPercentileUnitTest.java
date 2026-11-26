package com.arthur.refineloop.capability.units.compute;

import com.arthur.refineloop.capability.runtime.validation.FactMetrics;
import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.support.ValuationFixtures;
import com.arthur.refineloop.data.mapper.DailyBasicMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 验收：{@code calc_valuation_percentile} 的四个规定用例（正常序列 / 不足两年 / 全亏损 / 当日缺行）
 * + 行业缺失 + 分页边界。
 *
 * <p>断言一律走 {@link FactMetrics} 的指标路径（{@code percentile_3y.pe_ttm}），因为下游（interpret 单元、
 * L3 校验、溯源弹层）看到的正是这条路径——直接断言嵌套 Map 会漏掉「路径拼错」这类最贵的缺陷。
 */
@DisplayName("calc_valuation_percentile：BATCH 估值分位")
class CalcValuationPercentileUnitTest {

    private static final String TRADE_DATE = "20261126";
    private static final String CODE = "000001.SZ";
    private static final String INDUSTRY = "801780.SI";

    private JdbcTemplate jdbc;
    private CalcValuationPercentileUnit unit;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        unit = newUnit(CalcValuationPercentileUnit.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("正常序列：CDF 分位、nearest-rank 区间、亏损占比、同业相对位置")
    void computesPercentilesForNormalSeries() {
        ValuationFixtures.stock(jdbc, CODE, "平安银行");
        ValuationFixtures.stock(jdbc, "600000.SH", "浦发银行");
        ValuationFixtures.stock(jdbc, "600036.SH", "招商银行");
        ValuationFixtures.industryMember(jdbc, CODE, INDUSTRY, "20200101", null);
        ValuationFixtures.industryMember(jdbc, "600000.SH", INDUSTRY, "20200101", null);
        ValuationFixtures.industryMember(jdbc, "600036.SH", INDUSTRY, "20200101", null);
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        // 历史 pe 从 10 等差到 19.96，当日（最后一天）取 15.00：分位正好落在中位附近，手算可核
        ValuationFixtures.series(jdbc, CODE, dates.subList(0, 499),
                ValuationFixtures.linearPe("10", "0.02"), "1.5", "1.1", "4.8", "1000000");
        ValuationFixtures.dailyBasic(jdbc, CODE, TRADE_DATE, "15", "1.5", "1.1", "4.8", "1000000");
        // 同业当期 pe：20 / 30，与个股 15 一起得到中位数 20
        ValuationFixtures.dailyBasic(jdbc, "600000.SH", TRADE_DATE, "20", "1.2", "1.0", "5.0", "2000000");
        ValuationFixtures.dailyBasic(jdbc, "600036.SH", TRADE_DATE, "30", "1.8", "1.4", "3.0", "3000000");

        FactResult fact = single(compute(CODE));

        assertThat(fact.dataStatus()).isEqualTo(FactDataStatus.COMPLETE);
        assertThat(fact.payload()).containsEntry("data_status", "ok");
        // 500 个样本中 ≤15.00 的有 252 个（251 个历史值 + 当日值本身）
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isEqualByComparingTo("50.40");
        assertThat(metricUnit(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isEqualTo("pct");
        assertThat(metricValue(fact, ValuationFactSchema.BAND_PE_TTM_P10)).isEqualByComparingTo("10.98");
        assertThat(metricValue(fact, ValuationFactSchema.BAND_PE_TTM_P25)).isEqualByComparingTo("12.48");
        assertThat(metricValue(fact, ValuationFactSchema.BAND_PE_TTM_P50)).isEqualByComparingTo("14.98");
        assertThat(metricValue(fact, ValuationFactSchema.BAND_PE_TTM_P75)).isEqualByComparingTo("17.46");
        assertThat(metricValue(fact, ValuationFactSchema.BAND_PE_TTM_P90)).isEqualByComparingTo("18.96");
        assertThat(metricValue(fact, ValuationFactSchema.CURRENT_PE_TTM)).isEqualByComparingTo("15");
        assertThat(metricUnit(fact, ValuationFactSchema.CURRENT_PE_TTM)).isEqualTo("倍");
        assertThat(metricValue(fact, ValuationFactSchema.CURRENT_TOTAL_MV_YI)).isEqualByComparingTo("100");
        assertThat(metricValue(fact, ValuationFactSchema.LOSS_RATIO)).isEqualByComparingTo("0");
        assertThat(metricValue(fact, ValuationFactSchema.SAMPLE_DAYS)).isEqualByComparingTo("500");
        // vs_industry = 15 / 20 - 1
        assertThat(metricValue(fact, ValuationFactSchema.VS_INDUSTRY)).isEqualByComparingTo("-0.25");
        assertThat(metricValue(fact, ValuationFactSchema.INDUSTRY_PE_MEDIAN)).isEqualByComparingTo("20");
        assertThat(fact.payload()).containsEntry("data_status", "ok");
        assertThat(fact.notes()).anySatisfy(note -> assertThat(note).contains("盈利样本"));
    }

    @Test
    @DisplayName("不足两年：只输出当前值，无分位与区间（DEGRADED）")
    void degradesWhenWindowShorterThanTwoYears() {
        ValuationFixtures.stock(jdbc, CODE, "平安银行");
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 100);
        ValuationFixtures.series(jdbc, CODE, dates, ValuationFixtures.linearPe("10", "0.02"),
                "1.5", "1.1", "4.8", "1000000");

        FactResult fact = single(compute(CODE));

        assertThat(fact.dataStatus()).isEqualTo(FactDataStatus.DEGRADED);
        assertThat(fact.payload()).containsEntry("data_status", "degraded");
        assertThat(metricValue(fact, ValuationFactSchema.CURRENT_PE_TTM)).isEqualByComparingTo("11.98");
        assertThat(metricValue(fact, ValuationFactSchema.SAMPLE_DAYS)).isEqualByComparingTo("100");
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isNull();
        assertThat(fact.payload()).doesNotContainKey("percentile_3y").doesNotContainKey("band_3y");
        assertThat(fact.notes()).anySatisfy(note -> assertThat(note).contains("不足两年"));
    }

    @Test
    @DisplayName("全亏损：亏损占比 1，pe 分位不适用（DEGRADED），pb 分位仍可算")
    void degradesWhenAllSamplesAreLosses() {
        ValuationFixtures.stock(jdbc, CODE, "亏损王");
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        ValuationFixtures.series(jdbc, CODE, dates, index -> "-2.5", "1.5", "1.1", "0", "500000");

        FactResult fact = single(compute(CODE));

        assertThat(fact.dataStatus()).isEqualTo(FactDataStatus.DEGRADED);
        assertThat(metricValue(fact, ValuationFactSchema.LOSS_RATIO)).isEqualByComparingTo("1");
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isNull();
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PB)).isEqualByComparingTo("100.00");
        assertThat(fact.notes()).anySatisfy(note -> assertThat(note).contains("全亏损"));
    }

    @Test
    @DisplayName("当日缺行：MISSING 且不沿用昨日值，仍为池内每个标的产出一行")
    void marksMissingWhenCurrentRowAbsent() {
        ValuationFixtures.stock(jdbc, CODE, "平安银行");
        List<String> dates = ValuationFixtures.datesEndingAt("20261125", 500);
        ValuationFixtures.series(jdbc, CODE, dates, ValuationFixtures.linearPe("10", "0.02"),
                "1.5", "1.1", "4.8", "1000000");

        BatchFactResult result = compute(CODE, "600000.SH");

        assertThat(result.size()).isEqualTo(2);
        FactResult fact = factOf(result, CODE);
        assertThat(fact.dataStatus()).isEqualTo(FactDataStatus.MISSING);
        assertThat(fact.payload()).containsEntry("data_status", "missing")
                .containsEntry("ts_code", CODE)
                .containsEntry("trade_date", TRADE_DATE)
                .doesNotContainKey("current");
        assertThat(fact.notes()).anySatisfy(note -> assertThat(note).contains("缺行"));
    }

    @Test
    @DisplayName("行业归属缺失：省略同业相对位置并留痕，不影响分位计算")
    void omitsIndustryBlockWhenMembershipMissing() {
        ValuationFixtures.stock(jdbc, CODE, "平安银行");
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        ValuationFixtures.series(jdbc, CODE, dates, ValuationFixtures.linearPe("10", "0.02"),
                "1.5", "1.1", "4.8", "1000000");

        FactResult fact = single(compute(CODE));

        assertThat(fact.dataStatus()).isEqualTo(FactDataStatus.COMPLETE);
        assertThat(fact.payload()).doesNotContainKey("relative_industry");
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isNotNull();
        assertThat(fact.notes()).anySatisfy(note -> assertThat(note).contains("行业归属缺失"));
    }

    @Test
    @DisplayName("分页：页大小 2、池 5 只时不重不漏且不跨页串数据")
    void pagesDoNotMixStocks() {
        unit = newUnit(2);
        String[] codes = {"000001.SZ", "000002.SZ", "000003.SZ", "000004.SZ", "000005.SZ"};
        String[] currents = {"11", "12", "13", "14", "15"};
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        for (int i = 0; i < codes.length; i++) {
            ValuationFixtures.stock(jdbc, codes[i], "标的" + i);
            ValuationFixtures.series(jdbc, codes[i], dates.subList(0, 499),
                    ValuationFixtures.linearPe("10", "0.02"), "1.5", "1.1", "4.8", "1000000");
            ValuationFixtures.dailyBasic(jdbc, codes[i], TRADE_DATE, currents[i], "1.5", "1.1", "4.8", "1000000");
        }

        BatchFactResult result = compute(codes);

        assertThat(result.size()).isEqualTo(5);
        for (int i = 0; i < codes.length; i++) {
            FactResult fact = factOf(result, codes[i]);
            assertThat(fact.payload()).containsEntry("ts_code", codes[i]);
            assertThat(metricValue(fact, ValuationFactSchema.CURRENT_PE_TTM))
                    .isEqualByComparingTo(currents[i]);
        }
    }

    @Test
    @DisplayName("空池：返回空结果，不报错")
    void emptyPoolYieldsEmptyResult() {
        assertThat(unit.computeBatch(new FactContext(TRADE_DATE, "run-t9"),
                StockPool.of("EMPTY", List.of())).size()).isZero();
    }

    @Test
    @DisplayName("窗口起点：交易日减 3 个自然年，闰日回退到 2 月 28 日")
    void windowStartIsThreeCalendarYearsBack() {
        assertThat(CalcValuationPercentileUnit.windowStart("20261126")).isEqualTo("20231126");
        assertThat(CalcValuationPercentileUnit.windowStart("20240229")).isEqualTo("20210228");
    }

    @Test
    @DisplayName("payload 不含 null 值：缺失字段整体省略（否则 Map.copyOf 直接抛 NPE）")
    void payloadOmitsNullValues() {
        ValuationFixtures.stock(jdbc, CODE, "平安银行");
        List<String> dates = ValuationFixtures.datesEndingAt(TRADE_DATE, 500);
        // pb/ps/dv/市值全部缺失，只留 pe
        ValuationFixtures.series(jdbc, CODE, dates, ValuationFixtures.linearPe("10", "0.02"),
                null, null, null, null);

        FactResult fact = single(compute(CODE));

        assertThat(fact.payload()).containsKey("current");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) fact.payload().get("current");
        assertThat(current).containsOnlyKeys("pe_ttm");
        assertThat(metricValue(fact, ValuationFactSchema.CURRENT_PB)).isNull();
        assertThat(metricValue(fact, ValuationFactSchema.PERCENTILE_PE_TTM)).isEqualByComparingTo("100.00");
    }

    // ================================================================ 夹具

    private CalcValuationPercentileUnit newUnit(int pageSize) {
        return new CalcValuationPercentileUnit(
                SchemaTestSupport.mapper(jdbc, DailyBasicMapper.class),
                SchemaTestSupport.mapper(jdbc, SwIndustryMemberMapper.class),
                pageSize);
    }

    private BatchFactResult compute(String... codes) {
        return unit.computeBatch(new FactContext(TRADE_DATE, "run-t9"), StockPool.of("TEST", List.of(codes)));
    }

    private static FactResult single(BatchFactResult result) {
        assertThat(result.size()).isEqualTo(1);
        return result.facts().get(0);
    }

    private static FactResult factOf(BatchFactResult result, String tsCode) {
        return result.facts().stream()
                .filter(fact -> tsCode.equals(fact.tsCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未产出标的 " + tsCode + " 的事实"));
    }

    private static BigDecimal metricValue(FactResult fact, String path) {
        FactMetrics.Metric metric = FactMetrics.flatten(fact.payload()).get(path);
        return metric == null ? null : metric.value();
    }

    private static String metricUnit(FactResult fact, String path) {
        FactMetrics.Metric metric = FactMetrics.flatten(fact.payload()).get(path);
        return metric == null ? null : metric.unit();
    }
}
