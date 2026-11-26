/**
 * L3 五个校验器的实现（顺序见父包 {@code ValidationChain}）。
 *
 * <p><b>依赖约束</b>：只依赖父包契约、{@code capability.spi}、{@code common}、{@code config}；
 * 不触达数据库、不调用 LLM——校验必须是纯函数，否则无法在单测与黄金样本回放里复现。
 *
 * <p><b>不变量</b>：每个校验器只负责一个维度，错误里的 {@code rule} 名稳定（供统计与黄金样本比对）。
 */
package com.arthur.refineloop.capability.runtime.validation.validators;
