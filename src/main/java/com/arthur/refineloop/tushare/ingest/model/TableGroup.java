package com.arthur.refineloop.tushare.ingest.model;

/**
 * 41 张 Tushare 数据表的分组（与《数据中心-交互原型》的分组口径一致）。
 *
 * <p>分组口径：维表 6 / 行情 9 / 财务 10 / 事件 13 / 指数与市场 3，合计 41。
 */
public enum TableGroup {

    /** 维表：股票/交易日历/行业分类/指数基础信息等。 */
    DIMENSION("维表"),

    /** 行情：日线/复权因子/每日指标/资金流/涨跌停/周月线/指数日线/沪深港通。 */
    QUOTE("行情"),

    /** 财务：分红/财务指标/财务三表/业绩预告快报/审计/主营构成/披露计划。 */
    FINANCE("财务"),

    /** 事件：龙虎榜/大宗交易/停复牌/解禁/质押/回购/股东类。 */
    EVENT("事件"),

    /** 指数与市场：指数成分权重/融资融券。 */
    INDEX_MARKET("指数与市场");

    private final String label;

    TableGroup(String label) {
        this.label = label;
    }

    /** 中文显示名（接口 {@code group} 字段与前端分组筛选用）。 */
    public String label() {
        return label;
    }

    /** JavaBean 风格别名，便于模板/反射绑定。 */
    public String getLabel() {
        return label;
    }
}
