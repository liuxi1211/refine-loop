package com.arthur.refineloop.capability.units.compute;

import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.ComputeUnit;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.spi.UnitScope;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.data.entity.DailyBasicEntity;
import com.arthur.refineloop.data.mapper.DailyBasicMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 估值分位计算单元（能力详案第 5 章 Spec A 的 compute 端，{@code BATCH} 粒度）。
 *
 * <p><b>职责</b>：对标的池逐标的产出「当前估值 + 近三年经验 CDF 分位 + 分位区间 + 亏损样本占比 +
 * 申万一级同业相对位置」的结构化事实，落 {@code unit_fact} 由调用方完成（本类不写库）。
 *
 * <p><b>依赖方向</b>：{@code units → runtime/spi}；只依赖 {@code data} 的两个只读 Mapper。
 * 不调 LLM、不触发远端取数（数据就绪是 S0 数据门的职责）、不开事务。
 *
 * <p><b>批量纪律</b>：一次执行处理整池，但内部按 {@code PAGE_SIZE} 分页读三年序列并逐页计算，
 * 每页数据用完即弃——全市场三年序列（约 5000×750 行）一次性读入会打爆堆与连接池。
 * 同业中位数是横截面聚合，单独用「单日全市场」一次读取建立基线（见 {@link ValuationIndustryBaseline}）。
 *
 * <p><b>确定性</b>：时间只取 {@code ctx.tradeDate()}（窗口起点 = 交易日前 3 个自然年），
 * 不读 {@code LocalDate.now()}，因此同输入必同输出，可用固定夹具逐数值断言。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>输出行数 == 池内标的数（缺数据的标的是 {@code MISSING} 事实，不是「不输出」，否则下游无法区分
 *       「没算」和「没数据」）；</li>
 *   <li>任何数据不足都表达为 {@code dataStatus=DEGRADED/MISSING} + {@code notes}，不抛异常；</li>
 *   <li>计算口径冻结在 {@code docs/units/calc_valuation_percentile/SPEC.md}，改口径必须升版本。</li>
 * </ul>
 */
@Slf4j
@Component
@CapabilityUnit(id = ValuationFactSchema.UNIT_ID, version = ValuationFactSchema.VERSION,
        type = UnitType.COMPUTE, scope = UnitScope.BATCH)
public class CalcValuationPercentileUnit implements ComputeUnit {

    /**
     * 单页标的数：每页最多读 {@code 50 × 约750} 行三年序列（约 3.8 万行）。
     *
     * <p>不取更大的原因：单元在 S1 阶段与其它 4 个 compute 单元并行跑，单页越大，
     * 峰值堆占用与单条 SQL 的锁持有时长越高；50 的页数（全市场约 100 页）在本地 SQLite 上是毫秒级。
     */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final DailyBasicMapper dailyBasicMapper;
    private final SwIndustryMemberMapper industryMemberMapper;
    private final int pageSize;

    /**
     * 生产用构造器（Spring 装配）。
     *
     * <p>为什么显式标 {@code @Autowired}：本类有两个构造器（本构造器 + 测试用的页大小构造器），
     * Spring 遇到多构造器时若无注解会去找默认构造器并启动失败。标在「生产构造器」上，语义是
     * 「容器只认这一条装配路径」，测试仍可显式选择另一条。
     */
    @Autowired
    public CalcValuationPercentileUnit(DailyBasicMapper dailyBasicMapper,
                                       SwIndustryMemberMapper industryMemberMapper) {
        this(dailyBasicMapper, industryMemberMapper, DEFAULT_PAGE_SIZE);
    }

    /** 测试用：可改页大小以验证分页边界（不漏、不重、不跨页串数据）。 */
    CalcValuationPercentileUnit(DailyBasicMapper dailyBasicMapper,
                                SwIndustryMemberMapper industryMemberMapper,
                                int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize 必须为正数（当前 " + pageSize + "）");
        }
        this.dailyBasicMapper = dailyBasicMapper;
        this.industryMemberMapper = industryMemberMapper;
        this.pageSize = pageSize;
    }

    @Override
    public BatchFactResult computeBatch(FactContext ctx, StockPool pool) {
        if (ctx == null) {
            throw new IllegalArgumentException("FactContext 不能为空");
        }
        if (pool == null || pool.isEmpty()) {
            return BatchFactResult.empty();
        }
        String tradeDate = ctx.tradeDate();
        String windowStart = windowStart(tradeDate);
        ValuationIndustryBaseline baseline =
                ValuationIndustryBaseline.load(industryMemberMapper, dailyBasicMapper, tradeDate);

        List<FactResult> facts = new ArrayList<>(pool.size());
        List<String> codes = pool.tsCodes();
        int pages = 0;
        for (int from = 0; from < codes.size(); from += pageSize) {
            List<String> page = codes.subList(from, Math.min(from + pageSize, codes.size()));
            facts.addAll(computePage(page, tradeDate, windowStart, baseline));
            pages++;
        }
        log.info("[{}] 批量计算完成 tradeDate={} pool={} facts={} pages={} industries={} runId={}",
                unitId(), tradeDate, pool.size(), facts.size(), pages, baseline.industryCount(), ctx.runId());
        return new BatchFactResult(facts);
    }

    /** 单页：一次读整页的三年序列，按 ts_code 分组后逐标的纯函数计算。 */
    private List<FactResult> computePage(List<String> page, String tradeDate, String windowStart,
                                         ValuationIndustryBaseline baseline) {
        List<DailyBasicEntity> rows = dailyBasicMapper.selectWindow(page, windowStart, tradeDate);
        Map<String, List<DailyBasicEntity>> rowsByCode = new LinkedHashMap<>();
        for (String code : page) {
            rowsByCode.put(code, new ArrayList<>(8));
        }
        if (rows != null) {
            for (DailyBasicEntity row : rows) {
                List<DailyBasicEntity> bucket = rowsByCode.get(row.getTsCode());
                if (bucket != null) {
                    bucket.add(row);
                }
            }
        }
        List<FactResult> facts = new ArrayList<>(page.size());
        rowsByCode.forEach((code, codeRows) -> {
            String industryCode = baseline.industryOf(code);
            BigDecimal industryMedian = baseline.peTtmMedian(industryCode);
            facts.add(ValuationFactBuilder.build(new ValuationFactBuilder.ValuationInput(
                    code, tradeDate, windowStart, codeRows, industryCode, industryMedian)));
        });
        return facts;
    }

    /**
     * 窗口起点：交易日往前推 3 个自然年（详案 5.3「近 3 年，约 750 个交易日」）。
     *
     * <p>为什么用自然年减法而不是「往前数 750 个交易日」：后者要读交易日历，会把「交易日历未回填」
     * 变成单元失败；自然年减法结果确定、可单测，且窗口内实际交易日数由 {@code window.sample_days}
     * 如实记录，下游可据此判断覆盖率。
     */
    static String windowStart(String tradeDate) {
        return DateTimes.formatCompact(
                DateTimes.parseCompactDate(tradeDate).minusYears(ValuationFactSchema.WINDOW_YEARS));
    }
}
