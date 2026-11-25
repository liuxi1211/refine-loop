package com.arthur.refineloop.ops;

/**
 * 数据预览的过滤与列模式参数（替代 8 个位置参数中的 5 个）。
 *
 * <p>字段语义与 {@code GET /api/ops/tables/{table}/preview} 的查询参数一一对应；
 * 校验（日期格式、枚举取值、区间顺序）由 {@link TableOpsService#preview} 统一执行。
 *
 * @param code       按注册表 {@code codeColumn} 精确过滤，可空
 * @param start      按 {@code latestDateColumn} 起始，yyyyMMdd，可空
 * @param end        按 {@code latestDateColumn} 结束，yyyyMMdd，可空
 * @param columnMode key=幂等键 + 前 8 个非键列；all=全部列；空取 key
 * @param order      desc（默认）/ asc
 */
public record PreviewSpec(String code, String start, String end, String columnMode, String order) {
}
