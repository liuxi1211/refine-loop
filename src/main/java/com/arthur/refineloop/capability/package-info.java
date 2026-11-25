/**
 * 能力单元框架：SPI 契约、运行时骨架与（后续）具体能力单元。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code common}、{@code config}、{@code data}；
 * <b>禁止</b>依赖 {@code web}、{@code ops}、{@code tushare}、{@code user}、
 * {@code pipeline}、{@code judgment}、{@code report}、{@code version}
 * （由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>依赖方向只能是 {@code units → runtime → spi}，绝不反向；</li>
 *   <li>单元之间禁止互相调用，协作只通过 {@code FactBundle} 传递；</li>
 *   <li>只有 {@code capability.runtime.llm} 包可以直接接触 {@code ChatClient}。</li>
 * </ul>
 */
package com.arthur.refineloop.capability;
