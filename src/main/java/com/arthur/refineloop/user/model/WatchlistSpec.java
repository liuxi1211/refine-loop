package com.arthur.refineloop.user.model;

/**
 * 自选股写操作的服务层入参（创建/更新共用；{@code null} 字段表示不修改）。
 *
 * <p>{@code tsCode} 只在**创建**时使用（必填且必须匹配 {@code 000001.SZ} 形态）；更新时忽略
 * （标的不可改，换标的请删后新增）。{@code stockName} 为空时由服务层从 {@code stock_basic} 补全快照。
 */
public record WatchlistSpec(
        String tsCode,
        String stockName,
        String groupName,
        String note,
        Integer sortNo) {
}
