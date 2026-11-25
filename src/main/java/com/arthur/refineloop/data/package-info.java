/**
 * 数据访问层：全系统唯一 SQL 出入口（实体 / Mapper / 动态 SQL Provider / 批量执行器 / 类型处理器）。
 *
 * <p><b>依赖约束</b>：只允许依赖 {@code common}、{@code config}（仅配置属性）；<b>禁止</b>依赖
 * {@code ops}、{@code tushare}、{@code web}（由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>生产代码不使用 {@code JdbcTemplate}；</li>
 *   <li>动态 SQL 拼接的表名/列名只能来自 {@code TushareTableRegistry}，值一律 {@code #{}} 绑定；</li>
 *   <li>批量写统一走 {@code MyBatisBatchExecutor}（500 行/批 + 短事务）。</li>
 * </ul>
 */
package com.arthur.refineloop.data;
