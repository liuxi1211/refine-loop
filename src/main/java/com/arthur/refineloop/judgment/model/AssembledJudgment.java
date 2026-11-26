package com.arthur.refineloop.judgment.model;

import java.util.Map;

/**
 * 组装完成的判断：payload 与需要落到 {@code judgment} 冗余列的标量。
 *
 * <p>冗余列（{@code direction / horizon_days / expire_at / confidence}）是为了让 M3 的效果统计
 * 不必解析 JSON（架构 §5.2）——它们是 payload 的<b>投影</b>，必须由同一份组装结果产生，
 * 否则列与 payload 会出现「统计说看多、详情说看空」的分裂。
 *
 * @param judgmentId 判断编号（组装前生成，payload 里要写它）
 * @param direction  方向码（串到 {@code judgment.direction}）
 * @param horizonDays 有效期交易日数
 * @param expireAt   到期日 {@code yyyy-MM-dd}
 * @param confidence 置信度码（高/中/低）
 * @param payload    能力详案 3.2 完整 Schema
 */
public record AssembledJudgment(String judgmentId,
                                String direction,
                                int horizonDays,
                                String expireAt,
                                String confidence,
                                Map<String, Object> payload) {

    public AssembledJudgment {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
