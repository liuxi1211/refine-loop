package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.entity.JudgmentPriceEntity;
import com.arthur.refineloop.data.entity.UnitFactEntity;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.judgment.model.Benchmark;
import com.arthur.refineloop.judgment.model.MarketSnapshot;

/**
 * 判断存档上下文解析：股票名称与行情起点快照。
 *
 * <p>依赖方向：judgment → data。只读本地表，不触发远端拉数。
 * 不变量：行情起点缺失不是「降级事实」，而是不能生成可验证判断；必须以 6001 阻断存档并提示补数。
 */
public class JudgmentArchiveContextResolver {

    private final TushareLookupMapper lookupMapper;
    private final UnitFactMapper unitFactMapper;

    public JudgmentArchiveContextResolver(TushareLookupMapper lookupMapper, UnitFactMapper unitFactMapper) {
        this.lookupMapper = lookupMapper;
        this.unitFactMapper = unitFactMapper;
    }

    public String resolveStockName(String tsCode) {
        return lookupMapper.selectStockName(tsCode);
    }

    /** compute 单元（calc_/screen_ 命名约定）必须关联本次 run 的精确事实行，interpret 单元允许为空。 */
    public Long resolveFactId(String runId, String unitId, String unitVersion, String tsCode, String tradeDate) {
        UnitFactEntity fact = unitFactMapper.selectByKey(runId, unitId, unitVersion, tsCode, tradeDate);
        if (fact != null) {
            return fact.getId();
        }
        if (unitId.startsWith("calc_") || unitId.startsWith("screen_")) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "判断引用的 compute 事实不存在：runId=" + runId + ", unit=" + unitId
                            + ", stock=" + tsCode + ", tradeDate=" + tradeDate);
        }
        return null;
    }

    public MarketSnapshot resolveMarketSnapshot(String tsCode, String tradeDate, Benchmark benchmark) {
        JudgmentPriceEntity stock = lookupMapper.selectJudgmentStockPrice(tsCode, tradeDate);
        JudgmentPriceEntity index = lookupMapper.selectJudgmentIndexPrice(benchmark.indexCode(), tradeDate);
        if (stock == null || index == null) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "无法生成可验证判断：缺少 " + tradeDate + " 的个股 close/adj_factor 或基准 "
                            + benchmark.indexCode() + " 行情，请先补齐 daily、adj_factor、index_daily");
        }
        return new MarketSnapshot(stock.getClose(), stock.getAdjFactor(), index.getClose());
    }
}
