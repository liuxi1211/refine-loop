package com.arthur.refineloop.capability.units.compute;

import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.data.entity.DailyBasicEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 估值事实表组装：把「近三年窗口 + 当日行 + 行业基线」变成 {@code valuation_fact} 载荷。
 *
 * <p><b>职责边界</b>：这里只做确定性计算与口径标注，不判断「高估/低估」（详案 5.4 规则 3：
 * 阈值分档只提供给下游，不在本单元下结论）。所有数值都是一条可被 L3 校验的历史事实。
 *
 * <p><b>缺失与降级（详案 5.3/5.7）</b>：
 * <ul>
 *   <li>当日 {@code daily_basic} 缺行 → {@code data_status=missing}，只保留身份字段，
 *       <b>绝不</b>沿用昨日值（沿用会把「数据缺失」伪装成「估值没变」）；</li>
 *   <li>窗口覆盖不足 {@link ValuationFactSchema#MIN_SAMPLE_DAYS} 个交易日 → {@code degraded}，
 *       只输出当前值，不输出分位（两年以下的分位没有统计意义）；</li>
 *   <li>近三年样本全为亏损（{@code pe_ttm ≤ 0}）→ {@code degraded}，{@code pe_ttm} 分位不适用，
 *       亏损占比照常输出（下游据此改用 PS/PB 口径）；</li>
 *   <li>行业归属或同业样本缺失 → 省略 {@code relative_industry} 并写 {@code notes}，不猜行业。</li>
 * </ul>
 *
 * <p><b>单位口径</b>：每个指标叶子都带 {@code unit}（见 {@link ValuationFactSchema} 常量），
 * 这是 L3 校验「数值 + 单位」双比对的前提；没有单位的叶子会被判「无法核对口径」。
 *
 * <p><b>notes 里不写数字</b>：notes 会随事实表进入 prompt，若写成「盈利样本占 98.2%」，
 * 模型很可能把它抄进报告正文，而正文裸数字会被数字扫描判失败——占比已由 {@code loss_ratio_3y}
 * 指标承担，notes 只解释口径。
 */
public final class ValuationFactBuilder {

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int RATIO_SCALE = 4;
    private static final int MV_SCALE = 2;

    private ValuationFactBuilder() {
    }

    /**
     * 单只标的的计算输入。
     *
     * @param tsCode          股票代码
     * @param tradeDate       交易日 {@code yyyyMMdd}
     * @param windowStart     窗口起点（含）{@code yyyyMMdd}，仅用于写入事实（口径可溯源）
     * @param windowRows      该标的窗口内的全部行（按 trade_date 升序）
     * @param industryCode    申万一级行业代码；无归属为 null
     * @param industryPeMedian 行业当期 pe_ttm 正样本中位数；无样本为 null
     */
    public record ValuationInput(String tsCode,
                                 String tradeDate,
                                 String windowStart,
                                 List<DailyBasicEntity> windowRows,
                                 String industryCode,
                                 BigDecimal industryPeMedian) {

        public ValuationInput {
            windowRows = windowRows == null ? List.of() : List.copyOf(windowRows);
        }
    }

    /**
     * 组装单只标的的估值事实。
     *
     * @param input 计算输入
     * @return 事实（{@code dataStatus} 为 COMPLETE / DEGRADED / MISSING 之一）
     */
    public static FactResult build(ValuationInput input) {
        DailyBasicEntity current = findCurrent(input.windowRows(), input.tradeDate());
        if (current == null) {
            return missingFact(input);
        }
        Series pe = Series.of(input.windowRows(), DailyBasicEntity::getPeTtm, false);
        Series pb = Series.of(input.windowRows(), DailyBasicEntity::getPb, false);
        Series ps = Series.of(input.windowRows(), DailyBasicEntity::getPsTtm, false);
        Series dv = Series.of(input.windowRows(), DailyBasicEntity::getDvTtm, true);
        int sampleDays = countSampleDays(input.windowRows());
        List<String> notes = new ArrayList<>(4);

        boolean windowTooShort = sampleDays < ValuationFactSchema.MIN_SAMPLE_DAYS;
        if (windowTooShort) {
            notes.add("近三年样本交易日不足两年，仅输出当前值，不输出分位与区间");
        }
        boolean allLoss = pe.nonNull() > 0 && pe.samples().isEmpty();
        if (allLoss) {
            notes.add("近三年样本 pe_ttm 全部非正（全亏损），pe_ttm 分位不适用");
        }
        if (current.getPeTtm() != null && current.getPeTtm().signum() <= 0) {
            notes.add("当日 pe_ttm 非正（亏损），pe_ttm 分位不适用");
        }

        Map<String, BigDecimal> percentiles = windowTooShort
                ? Map.of()
                : percentiles(pe, pb, ps, current);
        // 全亏损（pe 样本全为非正）时即使 pb/ps 分位可算，事实也按降级输出：
        // 估值主口径失效，下游读事实时必须看到 degraded 才会切换口径（详案 5.7）
        FactDataStatus status = windowTooShort || allLoss || percentiles.isEmpty()
                ? FactDataStatus.DEGRADED
                : FactDataStatus.COMPLETE;
        if (status == FactDataStatus.DEGRADED && !windowTooShort && !allLoss) {
            notes.add("可计算分位的指标全部缺失，事实降级为仅当前值");
        }
        if (input.industryCode() == null) {
            notes.add("申万一级行业归属缺失，未输出同业相对位置");
        } else if (input.industryPeMedian() == null) {
            notes.add("行业当期 pe_ttm 正样本不足，未输出同业相对位置");
        }
        if (!pe.samples().isEmpty()) {
            notes.add("分位基于 pe_ttm 盈利样本计算，亏损样本占比见 loss_ratio_3y");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts_code", input.tsCode());
        payload.put("trade_date", input.tradeDate());
        payload.put("data_status", statusText(status));
        payload.put("current", currentBlock(current));
        putIfNotEmpty(payload, "percentile_3y", percentileBlock(percentiles));
        putIfNotEmpty(payload, "band_3y", bandBlock(pe, pb, ps, percentiles));
        putIfNotEmpty(payload, "loss_ratio_3y", leaf(lossRatio(pe), ValuationFactSchema.UNIT_RATIO));
        putIfNotEmpty(payload, "relative_industry",
                relativeIndustryBlock(input, current, percentiles.containsKey("pe_ttm")));
        payload.put("window", windowBlock(input, sampleDays));
        payload.put("notes", List.copyOf(notes));
        return new FactResult(input.tsCode(), ValuationFactSchema.UNIT_ID, ValuationFactSchema.VERSION,
                ValuationFactSchema.SCHEMA_VERSION, status, payload, notes);
    }

    // ================================================================ 各区块

    private static Map<String, Object> currentBlock(DailyBasicEntity current) {
        Map<String, Object> block = new LinkedHashMap<>();
        putIfNotEmpty(block, "pe_ttm", leaf(current.getPeTtm(), ValuationFactSchema.UNIT_MULTIPLE));
        putIfNotEmpty(block, "pb", leaf(current.getPb(), ValuationFactSchema.UNIT_MULTIPLE));
        putIfNotEmpty(block, "ps_ttm", leaf(current.getPsTtm(), ValuationFactSchema.UNIT_MULTIPLE));
        putIfNotEmpty(block, "dv_ttm", leaf(current.getDvTtm(), ValuationFactSchema.UNIT_PCT));
        putIfNotEmpty(block, "total_mv_yi", leaf(toYi(current.getTotalMv()), ValuationFactSchema.UNIT_YI_YUAN));
        return block;
    }

    /**
     * 三个估值指标的分位：当日值必须落在该指标的可用样本口径内（{@code pe_ttm/pb/ps_ttm > 0}），
     * 否则该指标分位不适用（亏损日的 PE 分位没有意义）。
     */
    private static Map<String, BigDecimal> percentiles(Series pe, Series pb, Series ps,
                                                       DailyBasicEntity current) {
        Map<String, BigDecimal> percentiles = new LinkedHashMap<>();
        putPercentile(percentiles, "pe_ttm",
                EmpiricalPercentile.cdf(pe.samples(), positiveOrNull(current.getPeTtm())));
        putPercentile(percentiles, "pb",
                EmpiricalPercentile.cdf(pb.samples(), positiveOrNull(current.getPb())));
        putPercentile(percentiles, "ps_ttm",
                EmpiricalPercentile.cdf(ps.samples(), positiveOrNull(current.getPsTtm())));
        return percentiles;
    }

    private static void putPercentile(Map<String, BigDecimal> target, String name, BigDecimal value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static Map<String, Object> percentileBlock(Map<String, BigDecimal> percentiles) {
        Map<String, Object> block = new LinkedHashMap<>();
        percentiles.forEach((name, value) -> block.put(name, leaf(value, ValuationFactSchema.UNIT_PCT)));
        if (!block.isEmpty()) {
            // method 只随分位一起出现：没有分位时不允许留下「method=empirical_cdf」的空壳块
            block.put("method", "empirical_cdf");
        }
        return block;
    }

    private static Map<String, Object> bandBlock(Series pe, Series pb, Series ps,
                                                 Map<String, BigDecimal> percentiles) {
        Map<String, Object> block = new LinkedHashMap<>();
        putIfNotEmpty(block, "pe_ttm", bandOf(pe, percentiles.containsKey("pe_ttm")));
        putIfNotEmpty(block, "pb", bandOf(pb, percentiles.containsKey("pb")));
        putIfNotEmpty(block, "ps_ttm", bandOf(ps, percentiles.containsKey("ps_ttm")));
        return block;
    }

    private static Map<String, Object> bandOf(Series series, boolean included) {
        if (!included || series.samples().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> band = new LinkedHashMap<>();
        for (int point : new int[]{10, 25, 50, 75, 90}) {
            putIfNotEmpty(band, "p" + point, leaf(
                    EmpiricalPercentile.nearestRank(series.samples(), point),
                    ValuationFactSchema.UNIT_MULTIPLE));
        }
        return band;
    }

    private static Map<String, Object> relativeIndustryBlock(ValuationInput input, DailyBasicEntity current,
                                                             boolean percentileAvailable) {
        if (input.industryCode() == null) {
            return Map.of();
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("industry_code", input.industryCode());
        putIfNotEmpty(block, "pe_ttm_median",
                leaf(input.industryPeMedian(), ValuationFactSchema.UNIT_MULTIPLE));
        if (percentileAvailable && current.getPeTtm() != null && current.getPeTtm().signum() > 0
                && input.industryPeMedian() != null && input.industryPeMedian().signum() > 0) {
            // 详案 5.4 规则 2：vs_industry = (个股 pe_ttm / 行业中位数) - 1
            BigDecimal ratio = current.getPeTtm()
                    .divide(input.industryPeMedian(), RATIO_SCALE + 2, RoundingMode.HALF_UP)
                    .subtract(ONE)
                    .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
            block.put("vs_industry", leaf(ratio, ValuationFactSchema.UNIT_RATIO));
        }
        return block;
    }

    private static Map<String, Object> windowBlock(ValuationInput input, int sampleDays) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("start_trade_date", input.windowStart());
        block.put("end_trade_date", input.tradeDate());
        block.put("sample_days", leaf(BigDecimal.valueOf(sampleDays),
                ValuationFactSchema.UNIT_TRADE_DAYS));
        return block;
    }

    // ================================================================ 状态与工具

    private static String statusText(FactDataStatus status) {
        return switch (status) {
            case COMPLETE -> ValuationFactSchema.STATUS_OK;
            case DEGRADED -> ValuationFactSchema.STATUS_DEGRADED;
            case MISSING -> ValuationFactSchema.STATUS_MISSING;
        };
    }

    private static FactResult missingFact(ValuationInput input) {
        List<String> notes = List.of("当日 daily_basic 缺行，未输出估值事实（禁止沿用昨日值）");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts_code", input.tsCode());
        payload.put("trade_date", input.tradeDate());
        payload.put("data_status", ValuationFactSchema.STATUS_MISSING);
        payload.put("notes", notes);
        return new FactResult(input.tsCode(), ValuationFactSchema.UNIT_ID, ValuationFactSchema.VERSION,
                ValuationFactSchema.SCHEMA_VERSION, FactDataStatus.MISSING, payload, notes);
    }

    private static DailyBasicEntity findCurrent(List<DailyBasicEntity> rows, String tradeDate) {
        for (DailyBasicEntity row : rows) {
            if (tradeDate.equals(row.getTradeDate())) {
                return row;
            }
        }
        return null;
    }

    private static int countSampleDays(List<DailyBasicEntity> rows) {
        Set<String> days = new LinkedHashSet<>();
        for (DailyBasicEntity row : rows) {
            if (row.getTradeDate() != null) {
                days.add(row.getTradeDate());
            }
        }
        return days.size();
    }

    private static BigDecimal lossRatio(Series pe) {
        if (pe.nonNull() == 0) {
            return null;
        }
        return BigDecimal.valueOf(pe.excluded())
                .divide(BigDecimal.valueOf(pe.nonNull()), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal toYi(BigDecimal wan) {
        return wan == null ? null : wan.divide(TEN_THOUSAND, MV_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> leaf(BigDecimal value, String unit) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> leaf = new LinkedHashMap<>(2);
        leaf.put("value", value);
        leaf.put("unit", unit);
        return leaf;
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, Map<String, Object> block) {
        if (block != null && !block.isEmpty()) {
            target.put(key, block);
        }
    }

    // ================================================================ 单指标样本序列

    /**
     * 单指标的三段样本：可用正样本（升序）、非空样本数、被排除的样本数。
     *
     * <p>被排除指不满足口径（{@code pe_ttm/pb/ps_ttm ≤ 0}）；{@code dv_ttm} 允许 0（不分红是合法取值）。
     */
    private record Series(List<BigDecimal> samples, int nonNull, int excluded) {

        static Series of(List<DailyBasicEntity> rows, Function<DailyBasicEntity, BigDecimal> getter,
                         boolean allowZero) {
            List<BigDecimal> samples = new ArrayList<>(rows.size());
            int nonNull = 0;
            int excluded = 0;
            for (DailyBasicEntity row : rows) {
                BigDecimal value = getter.apply(row);
                if (value == null) {
                    continue;
                }
                nonNull++;
                if (allowZero ? value.signum() >= 0 : value.signum() > 0) {
                    samples.add(value);
                } else {
                    excluded++;
                }
            }
            samples.sort(Comparator.naturalOrder());
            return new Series(List.copyOf(samples), nonNull, excluded);
        }
    }
}
