package com.arthur.refineloop.capability.spi;

/**
 * 单元执行上下文。
 *
 * <p>时间一律从 {@code ctx.tradeDate()} 取，禁止单元内部 {@code LocalDate.now()}；
 * {@code runId} 用于日志/用量贯穿。
 *
 * @param tradeDate 交易日 {@code yyyyMMdd}
 * @param runId     流水线运行标识，可为空串
 */
public record FactContext(String tradeDate, String runId) {

    public FactContext {
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd：" + tradeDate);
        }
        runId = runId == null ? "" : runId;
    }
}
