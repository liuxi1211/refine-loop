package com.arthur.refineloop.capability.units.compute;

import com.arthur.refineloop.data.entity.DailyBasicEntity;
import com.arthur.refineloop.data.entity.SwIndustryMemberEntity;
import com.arthur.refineloop.data.mapper.DailyBasicMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 申万一级行业的当期估值基线：{@code ts_code → L1 行业} + {@code L1 行业 → pe_ttm 中位数}。
 *
 * <p><b>为什么一次全量预加载</b>：同业相对位置是横截面聚合（同一交易日、同一行业、所有标的），
 * 无法按页增量算——分页只适用于「逐标的的历史窗口」。这里读的是**单日**全市场（约 5000 行）
 * 与单日行业成分（约 5000 行），不是历史序列，因此不违反「禁止一次性加载全市场」的批量纪律。
 *
 * <p><b>口径</b>（能力详案 5.4 规则 2）：中位数只取 {@code pe_ttm > 0} 的正样本（亏损样本的负 PE
 * 混进中位数会凭空压低行业估值水平），按行业分别计算，保留 4 位。
 *
 * <p><b>不变量</b>：构造后只读；行业或成分缺失时返回 null（下游据此省略 {@code relative_industry}
 * 并写 {@code notes}），绝不用默认值顶替。
 */
public final class ValuationIndustryBaseline {

    private static final int MEDIAN_SCALE = 4;

    private static final ValuationIndustryBaseline EMPTY =
            new ValuationIndustryBaseline(Map.of(), Map.of());

    private final Map<String, String> industryByCode;
    private final Map<String, BigDecimal> peMedianByIndustry;

    private ValuationIndustryBaseline(Map<String, String> industryByCode,
                                      Map<String, BigDecimal> peMedianByIndustry) {
        this.industryByCode = Map.copyOf(industryByCode);
        this.peMedianByIndustry = Map.copyOf(peMedianByIndustry);
    }

    /**
     * 读取目标交易日的行业基线。
     *
     * @param memberMapper    行业成分查询
     * @param dailyBasicMapper 当日每日指标查询
     * @param tradeDate       交易日 {@code yyyyMMdd}
     * @return 基线；成分表为空时返回空基线（不是异常：新库尚未回填成分属可预期状态）
     */
    public static ValuationIndustryBaseline load(SwIndustryMemberMapper memberMapper,
                                                 DailyBasicMapper dailyBasicMapper,
                                                 String tradeDate) {
        Map<String, String> industry = loadIndustryMembers(memberMapper, tradeDate);
        if (industry.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<BigDecimal>> samplesByIndustry = new HashMap<>();
        for (DailyBasicEntity row : dailyBasicMapper.selectByTradeDate(tradeDate)) {
            String industryCode = industry.get(row.getTsCode());
            BigDecimal pe = row.getPeTtm();
            if (industryCode == null || pe == null || pe.signum() <= 0) {
                continue;
            }
            samplesByIndustry.computeIfAbsent(industryCode, key -> new ArrayList<>()).add(pe);
        }
        Map<String, BigDecimal> medians = new HashMap<>();
        samplesByIndustry.forEach((industryCode, samples) -> {
            BigDecimal median = median(samples);
            if (median != null) {
                medians.put(industryCode, median);
            }
        });
        return new ValuationIndustryBaseline(industry, medians);
    }

    /** 空基线：成分表未回填时使用（不抛异常，下游只省略同业相对位置）。 */
    public static ValuationIndustryBaseline empty() {
        return EMPTY;
    }

    /** 标的的申万一级行业代码；无归属返回 null。 */
    public String industryOf(String tsCode) {
        return tsCode == null ? null : industryByCode.get(tsCode);
    }

    /** 行业当期 pe_ttm 正样本中位数；无样本返回 null。 */
    public BigDecimal peTtmMedian(String industryCode) {
        return industryCode == null ? null : peMedianByIndustry.get(industryCode);
    }

    /** 已建立的行业数（日志与测试用）。 */
    public int industryCount() {
        return peMedianByIndustry.size();
    }

    private static Map<String, String> loadIndustryMembers(SwIndustryMemberMapper memberMapper,
                                                           String tradeDate) {
        Map<String, String> industry = new HashMap<>();
        List<SwIndustryMemberEntity> members = memberMapper.selectCurrentMembers(tradeDate);
        if (members == null) {
            return industry;
        }
        for (SwIndustryMemberEntity member : members) {
            if (member.getTsCode() == null || member.getL1Code() == null) {
                continue;
            }
            // SQL 已按 in_date 升序：后出现的（更晚纳入）覆盖先前的，等价于「取最新归属」
            industry.put(member.getTsCode(), member.getL1Code());
        }
        return industry;
    }

    /** 中位数：偶数个取中间两个的均值，null 元素不参与。 */
    static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values.size());
        for (BigDecimal value : values) {
            if (value != null) {
                sorted.add(value);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        int middle = size / 2;
        BigDecimal median = size % 2 == 1
                ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle))
                        .divide(BigDecimal.valueOf(2), MEDIAN_SCALE, RoundingMode.HALF_UP);
        return median.setScale(MEDIAN_SCALE, RoundingMode.HALF_UP);
    }
}
