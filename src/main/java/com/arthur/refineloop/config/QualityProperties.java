package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * T5 数据质量门（S0）阈值配置（前缀 {@code quality}，资源文件 quality.yml）。
 *
 * <p>{@code application.yml} 已 {@code spring.config.import: optional:classpath:quality.yml}；
 * 启动类 {@code @ConfigurationPropertiesScan} 负责注册本类。阈值全部可调，不达标联动由上层流水线处理。
 */
@ConfigurationProperties(prefix = "quality")
public class QualityProperties {

    /** 第①层：当日必须有 SUCCESS 作业的关键表（默认 5 张，可配）。 */
    private List<String> criticalTables = new ArrayList<>(List.of(
            "daily", "adj_factor", "daily_basic", "fina_indicator", "trade_cal"));

    /** 第②层：daily 当日行数 vs stock_basic 在市(L)总数的允许偏差（比例绝对值，默认 0.02 = 2%）。 */
    private double rowCountDeviationThreshold = 0.02D;

    /** 第②层：fina_indicator 最新 ann_date 距检查日的最大天数，超限 WARN（默认 30 天）。 */
    private int finaIndicatorMaxLagDays = 30;

    public List<String> getCriticalTables() {
        return criticalTables;
    }

    public void setCriticalTables(List<String> criticalTables) {
        this.criticalTables = criticalTables;
    }

    public double getRowCountDeviationThreshold() {
        return rowCountDeviationThreshold;
    }

    public void setRowCountDeviationThreshold(double rowCountDeviationThreshold) {
        this.rowCountDeviationThreshold = rowCountDeviationThreshold;
    }

    public int getFinaIndicatorMaxLagDays() {
        return finaIndicatorMaxLagDays;
    }

    public void setFinaIndicatorMaxLagDays(int finaIndicatorMaxLagDays) {
        this.finaIndicatorMaxLagDays = finaIndicatorMaxLagDays;
    }
}
