package com.arthur.refineloop.tushare.quality;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 质量门单项检查结果（S0 双层判定的最小展示单元）。
 *
 * <p>{@code status}：{@code PASS} / {@code WARN} / {@code FAIL} / {@code SKIP}；
 * {@code layer}：1=当日关键表作业状态层，2=行数一致性/字段 sanity/时效层；
 * {@code expected}/{@code actual} 为前端展示文案（不参与程序判定）。
 */
public record QualityCheckItem(
        String code,
        int layer,
        String name,
        String status,
        String expected,
        String actual,
        String message) {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";

    /** 判定辅助；{@code @JsonIgnore} 防止被当成 JSON 字段写出后无法反序列化。 */
    @JsonIgnore
    public boolean isFail() {
        return FAIL.equals(status);
    }

    @JsonIgnore
    public boolean isWarn() {
        return WARN.equals(status);
    }

    @JsonIgnore
    public boolean isSkip() {
        return SKIP.equals(status);
    }
}
