package com.arthur.refineloop.capability.spi;

import java.util.List;

/**
 * 机械降级判断的生成依据：由框架（非 LLM）按判断 Schema 骨架生成 {@code judgmentBlock}。
 *
 * <p>为什么由框架生成：降级判断的唯一目的是「让标的仍进入验证链路」，因此必须字段齐全、
 * 可被 {@code emitJudgment} 与正常判断单路径消费；若交给 LLM 生成，等于把不可信输出直接存档。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@code expireAt} 为业务口径自然日 {@code yyyy-MM-dd}（判断 Schema 的 {@code direction.expire_at} 口径）；</li>
 *   <li>{@code reasons} 至少一条，写清降级原因（会落 {@code data_gaps}）。</li>
 * </ul>
 *
 * @param unitId      产出单元 id
 * @param unitVersion 产出单元版本
 * @param stockCode   标的代码
 * @param tradeDate   交易日 {@code yyyyMMdd}
 * @param expireAt    判断到期日 {@code yyyy-MM-dd}
 * @param reasons     降级原因（非空）
 */
public record DegradedJudgment(
        String unitId,
        String unitVersion,
        String stockCode,
        String tradeDate,
        String expireAt,
        List<String> reasons) {

    public DegradedJudgment {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("unitId 不能为空");
        }
        if (unitVersion == null || unitVersion.isBlank()) {
            throw new IllegalArgumentException("unitVersion 不能为空");
        }
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("stockCode 不能为空");
        }
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd：" + tradeDate);
        }
        if (expireAt == null || !expireAt.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("expireAt 必须为 yyyy-MM-dd：" + expireAt);
        }
        reasons = reasons == null || reasons.isEmpty()
                ? List.of("L3 校验失败后降级（原因未记录）")
                : List.copyOf(reasons);
    }
}
