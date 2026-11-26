/**
 * 能力单元运行时骨架：注册、事实存取、版本登记、交易日历、L3 校验链。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code spi}、{@code common}、{@code config}、{@code data}；
 * <b>禁止</b>依赖 {@code capability.units} 与 {@code web/ops/tushare/user/pipeline/judgment}
 * （由 {@code ArchitectureRulesTest} 校验）。{@code validation} 子包额外禁止依赖 {@code data}。
 *
 * <p><b>不变量</b>：本包内的运行时类保持为纯类（构造器注入、无 Spring 注解），
 * 由 {@code config/CapabilityConfig} 装配，单测可直接 {@code new}。
 */
package com.arthur.refineloop.capability.runtime;
