/**
 * 能力单元运行时骨架：注册、事实存取、视图装配。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code spi}、{@code common}、{@code config}、{@code data}；
 * <b>禁止</b>依赖 {@code capability.units}（由 {@code ArchitectureRulesTest} 校验）。
 */
package com.arthur.refineloop.capability.runtime;
