package com.arthur.refineloop.judgment.model;

import java.util.List;
import java.util.Map;

/**
 * 待存档的判断内容：interpret 单元的产出 + 存档方补充的降级/缺口信息。
 *
 * <p>判断块用 {@code Map<String,Object>} 而不是强类型 record：判断块是<b>契约</b>（能力详案 3.1/3.2），
 * 各 interpret 单元维度不同、字段仍在演进；存档器若 import 某个单元的类型，单元升版就会牵动存档器，
 * 违背「框架骨架写一次基本不动」。字段的合法性由 Schema 校验链（T8）在产出侧保证，
 * 存档器只做「主判断必需字段」的装配级校验。
 *
 * @param judgmentBlock 判断块（正常由 LLM 产出并通过 L3；降级由框架机械生成）
 * @param degraded      是否降级判断（降级样本在 M3 效果统计中默认过滤）
 * @param dataGaps      存档方补充的数据缺口（如行业归属缺失），与判断块内的 {@code data_gaps} 合并
 */
public record JudgmentInput(Map<String, Object> judgmentBlock, boolean degraded, List<String> dataGaps) {

    public JudgmentInput {
        judgmentBlock = judgmentBlock == null ? Map.of() : Map.copyOf(judgmentBlock);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
