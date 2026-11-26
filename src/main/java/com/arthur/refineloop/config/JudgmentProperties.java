package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 判断域配置（{@code judgment.*}）。
 *
 * <p>只有一项且必须可配的原因：{@code benchmark.index_code} 会被<b>固化进每份判断</b>
 * （能力详案 3.3 契约 7），换基准指数等于换归因口径，属于「方法版本级别」的变更；
 * 写死在代码里会让换基准必须改代码并重新发版，写进配置则至少能一眼看到当前口径。
 *
 * <p>注意：改这个值<b>不会</b>自动让历史判断的基准跟着变（这正是不变量：旧判断的基准永久固化）。
 */
@ConfigurationProperties(prefix = "judgment")
public class JudgmentProperties {

    /** 基准指数代码，默认沪深 300（P1 的超额收益基准）。 */
    private String benchmarkIndexCode = "000300.SH";

    public String getBenchmarkIndexCode() {
        return benchmarkIndexCode;
    }

    public void setBenchmarkIndexCode(String benchmarkIndexCode) {
        this.benchmarkIndexCode = benchmarkIndexCode == null || benchmarkIndexCode.isBlank()
                ? "000300.SH"
                : benchmarkIndexCode.trim();
    }
}
