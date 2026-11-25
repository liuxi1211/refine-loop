/**
 * 运维读模型域：数据中心运维台的只读聚合（表总览/详情/预览、作业历史、质量历史、今日统计）。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code common}、{@code config}、{@code data}、{@code tushare}；
 * <b>禁止</b>依赖 {@code web}（由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>本域只读：唯一例外是每日增量开关（写 {@code sys_config}）与单表动作触发（委托 {@code IngestFacade}）；</li>
 *   <li>表名/列名白名单只能来自 {@code TushareTableRegistry}；</li>
 *   <li>对外只输出 {@link com.arthur.refineloop.ops.OpsViews} 中的不可变 record。</li>
 * </ul>
 */
package com.arthur.refineloop.ops;
