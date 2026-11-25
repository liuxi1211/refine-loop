package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.data.entity.LlmUsageEntity;
import com.arthur.refineloop.data.mapper.LlmUsageMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;

/**
 * LLM 用量记账：每次成功调用写一行 {@code llm_usage}。
 *
 * <p>费用按 profile 配置的“每百万 token 单价”估算；默认 0（订阅制/未配置价格），token 照常记账。
 * 失败调用不写 0-token 脏行，只由调用方记日志。
 */
@Slf4j
public class LlmUsageStore {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int COST_SCALE = 6;

    private final LlmUsageMapper mapper;
    private final Clock clock;

    public LlmUsageStore(LlmUsageMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 写入一条用量记录。
     *
     * @param cost 费用估算（由 {@link #estimateCost} 得到）
     * @return 主键 id
     */
    public long record(String runId,
                       String unitId,
                       String modelProfile,
                       String model,
                       int promptTokens,
                       int completionTokens,
                       int totalTokens,
                       BigDecimal cost) {
        BigDecimal safeCost = cost == null ? BigDecimal.ZERO : cost;
        LlmUsageEntity entity = new LlmUsageEntity();
        entity.setRunId(blankToNull(runId));
        entity.setUnitId(blankToNull(unitId));
        entity.setModelProfile(blankToNull(modelProfile));
        entity.setModel(model == null || model.isBlank() ? "unknown" : model);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(totalTokens);
        entity.setCost(safeCost);
        entity.setCreatedAt(DateTimes.now(clock));
        mapper.insert(entity);
        log.debug("[llm] 用量记账 id={} profile={} model={} tokens={}/{}/{} cost={}",
                entity.getId(), modelProfile, entity.getModel(), promptTokens, completionTokens, totalTokens, safeCost);
        return entity.getId() == null ? -1L : entity.getId();
    }

    /** 费用估算：按百万 token 单价线性计算，6 位小数 HALF_UP。 */
    public static BigDecimal estimateCost(int promptTokens,
                                          int completionTokens,
                                          BigDecimal inputPricePer1m,
                                          BigDecimal outputPricePer1m) {
        BigDecimal inputPrice = inputPricePer1m == null ? BigDecimal.ZERO : inputPricePer1m;
        BigDecimal outputPrice = outputPricePer1m == null ? BigDecimal.ZERO : outputPricePer1m;
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(promptTokens, 0))
                .multiply(inputPrice)
                .divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(completionTokens, 0))
                .multiply(outputPrice)
                .divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
