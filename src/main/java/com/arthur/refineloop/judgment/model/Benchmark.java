package com.arthur.refineloop.judgment.model;

/**
 * 判断基准（能力详案 3.3 契约 7）：存档时固化，M3 归因定量拆解直接消费。
 *
 * <p><b>为什么必须固化而不是归因时再查</b>：行业归属会随时间变动（申万成分调整、公司主营变化），
 * 事后按 {@code stock_basic} 映射会让「相对行业」的基准悄悄漂移，归因结论就不可复现了。
 *
 * @param indexCode    基准指数代码（默认 {@code 000300.SH}，来自配置）
 * @param industryCode 申万一级行业代码；无归属为 null（此时 {@code data_gaps} 必须留下说明）
 */
public record Benchmark(String indexCode, String industryCode) {

    public Benchmark {
        if (indexCode == null || indexCode.isBlank()) {
            throw new IllegalArgumentException("indexCode 不能为空：基准指数是归因的必填口径");
        }
    }
}
