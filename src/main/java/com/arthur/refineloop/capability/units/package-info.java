/**
 * 能力单元实现层：具体的 compute / interpret 单元。<b>唯一</b>允许继承
 * {@code AbstractInterpretUnit} 的包。
 *
 * <p><b>职责</b>：把《核心能力体系设计详案》的单元 Spec 翻译成代码——compute 单元只做确定性计算，
 * interpret 单元只提供四个差异点（promptId / schemaId / buildFactsPayload / mapOutput）。
 *
 * <p><b>依赖方向</b>：{@code units → runtime → spi}；本包可以依赖 {@code common}、{@code data}
 * （只读 Mapper）与 {@code config} 的配置属性类，<b>不得</b>依赖 {@code web} / {@code ops} /
 * {@code tushare} / {@code user} / {@code judgment} / {@code pipeline} / {@code report} / {@code version}。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>单元之间禁止互相调用——协作只通过 {@code FactBundle}，编排权属于流水线层；</li>
 *   <li>单元内禁止 {@code LocalDate.now()}（时间一律取 {@code FactContext.tradeDate()}）、
 *       禁止调用 LLM（compute）、禁止触发远端取数（数据就绪是 S0 数据门的职责）；</li>
 *   <li>数据不足不是异常，而是 {@code dataStatus=DEGRADED/MISSING} + {@code notes}（显式降级）。</li>
 * </ul>
 */
package com.arthur.refineloop.capability.units;
