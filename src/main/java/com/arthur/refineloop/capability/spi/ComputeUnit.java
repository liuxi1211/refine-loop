package com.arthur.refineloop.capability.spi;

/**
 * 计算单元（L1）：确定性、无 LLM、无远端取数。
 *
 * <p>M1 全部 compute 单元为 {@link UnitScope#BATCH}，实现 {@link #computeBatch}；
 * {@link #compute} 仅为 PER_STOCK 保留，默认抛异常避免 BATCH 单元误实现。
 */
public interface ComputeUnit extends CapabilityUnit {

    /**
     * BATCH 粒度：一次处理整个标的池，产出批量事实。
     *
     * @param ctx  执行上下文（tradeDate / runId）
     * @param pool 标的池
     * @return 逐标的或汇总事实
     */
    default BatchFactResult computeBatch(FactContext ctx, StockPool pool) {
        throw new UnsupportedOperationException(unitId() + " 不支持 BATCH 粒度");
    }

    /**
     * PER_STOCK 粒度：逐标的计算。M1 不使用，保留给后续按标的的确定性计算。
     *
     * @param ctx 执行上下文
     * @return 单标的事实
     */
    default FactResult compute(FactContext ctx) {
        throw new UnsupportedOperationException(unitId() + " 不支持 PER_STOCK 粒度");
    }
}
