package com.arthur.refineloop.capability.spi;

import java.util.List;
import java.util.Map;

/**
 * 解读单元的产出：Markdown 报告 + 结构化判断块。
 *
 * <p>{@code degraded=true} 表示本次为降级产出（数据不足/校验失败），
 * 降级时 {@code judgmentBlock} 仍应存在，保证 emitJudgment 走同一条消费路径。
 *
 * @param reportMd      报告正文
 * @param judgmentBlock 结构化判断块
 * @param degraded      是否降级
 * @param dataGaps      数据缺口说明
 */
public record InterpretResult(
        String reportMd,
        Map<String, Object> judgmentBlock,
        boolean degraded,
        List<String> dataGaps) {

    public InterpretResult {
        reportMd = reportMd == null ? "" : reportMd;
        judgmentBlock = judgmentBlock == null ? Map.of() : Map.copyOf(judgmentBlock);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
