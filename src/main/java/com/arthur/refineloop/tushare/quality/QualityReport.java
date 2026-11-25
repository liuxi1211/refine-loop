package com.arthur.refineloop.tushare.quality;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量门一次检查的结构化报告（GET /api/ops/quality/latest、POST /api/ops/quality/run 的 data）。
 *
 * <p>{@code result}：{@code EMPTY}（非交易日/无数据，逐项 SKIP，不算失败）/
 * {@code PASS} / {@code PASS_WITH_WARN} / {@code FAIL}。
 *
 * @param checkId  quality_check_log 主键；落库前的内存报告用 0 占位
 * @param checkTime 检查执行时间（Asia/Shanghai 本地时间）
 * @param tradeDate 被检查的业务日期 yyyyMMdd
 */
public record QualityReport(
        long checkId,
        LocalDateTime checkTime,
        String tradeDate,
        String triggerType,
        String result,
        long durationMs,
        List<QualityCheckItem> items) {

    public static final String RESULT_EMPTY = "EMPTY";
    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_PASS_WITH_WARN = "PASS_WITH_WARN";
    public static final String RESULT_FAIL = "FAIL";

    /** 汇总各状态数量（历史列表展示用）。 */
    public long count(String status) {
        if (items == null) {
            return 0L;
        }
        return items.stream().filter(i -> status.equals(i.status())).count();
    }
}
