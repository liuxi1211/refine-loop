package com.arthur.refineloop.judgment.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存档结果：写入的判断编号 + 如需知晓的取代关系。
 *
 * <p>调用方（流水线 S4、手动单股分析）在日志/看板里要能回答两件事：这次存档产生了哪个编号、
 * 它取代了谁。因此返回 record 而不是裸 String。
 *
 * @param judgmentId           新判断编号
 * @param supersededJudgmentId 被取代的旧判断编号；无旧判断为 null
 * @param reaffirm             是否为重申判断（同标的更早交易日、仍存续且同方向）
 * @param payload              实际落库的判断 payload（供调用方写报告/日志，不参与后续修改）
 */
public record JudgmentArchiveResult(String judgmentId,
                                    String supersededJudgmentId,
                                    boolean reaffirm,
                                    Map<String, Object> payload) {

    public JudgmentArchiveResult {
        payload = payload == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
