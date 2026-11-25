package com.arthur.refineloop.tushare.ingest.model;

/**
 * Tushare 表的更新策略（决定 T4 编排按什么维度切分拉取与计算增量起点）。
 *
 * <p>与《Tushare 接口对接手册》第 4 章五种更新策略一一对应。
 */
public enum IngestStrategy {

    /** 一次性全量：维表类（stock_basic / trade_cal / index_basic / sw_industry / sw_industry_member）。 */
    ONE_SHOT("一次性全量"),

    /** per-stock：财务类与股东类，按股票清单逐只拉取。 */
    PER_STOCK("per-stock"),

    /** 按交易日：全市场行情类，按交易日逐日拉取。 */
    PER_TRADE_DATE("按交易日"),

    /** 按月窗口：涨跌停/周月线/解禁/回购/停复牌等按月拆分。 */
    PER_MONTH("按月窗口"),

    /** 按期/公告日：披露计划/质押统计/券商预测等按报告期或公告日拉取。 */
    PER_PERIOD("按期/公告日");

    private final String label;

    IngestStrategy(String label) {
        this.label = label;
    }

    /** 中文显示名（接口 {@code strategyLabel} 字段用）。 */
    public String label() {
        return label;
    }

    /** JavaBean 风格别名，便于模板/反射绑定。 */
    public String getLabel() {
        return label;
    }
}
