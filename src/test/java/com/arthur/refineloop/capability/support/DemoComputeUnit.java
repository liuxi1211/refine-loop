package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.ComputeUnit;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.spi.UnitScope;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;

import java.util.List;
import java.util.Map;

/**
 * T6 验收用 demo compute 单元：只存在于测试源，验证「注册→执行→落 unit_fact」全通。
 */
@CapabilityUnit(id = "demo_compute", version = "v1.0", type = UnitType.COMPUTE, scope = UnitScope.BATCH)
public class DemoComputeUnit implements ComputeUnit {

    @Override
    public BatchFactResult computeBatch(FactContext ctx, StockPool pool) {
        List<FactResult> facts = pool.tsCodes().stream()
                .map(tsCode -> new FactResult(
                        tsCode,
                        unitId(),
                        version(),
                        version(),
                        FactDataStatus.COMPLETE,
                        Map.of("close", 10.0, "pctChg", 1.5, "tradeDate", ctx.tradeDate()),
                        List.of()))
                .toList();
        return new BatchFactResult(facts);
    }
}
