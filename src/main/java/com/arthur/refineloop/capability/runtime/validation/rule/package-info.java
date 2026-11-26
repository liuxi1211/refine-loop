/**
 * 单元特有校验规则引擎：规则实现是代码（可单测），规则清单是资源（{@code *.rules.json}）。
 *
 * <p><b>为什么这样拆</b>：能力详案 6.5 这类「current_side 与 verification_rules 数量联动」
 * 属于逻辑，写死在 JSON 里会变成自造表达式语言；但「哪个单元启用哪几条、参数多少」必须能随
 * 单元版本一起换文件（升版即换 rules.json）。于是规则=代码、装配=资源。
 *
 * <p><b>依赖约束</b>：只依赖 {@code capability.spi}、{@code common} 与父包契约；
 * 规则实现由 {@code config} 装配进注册表，本包不 import 任何业务单元。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>规则 id 全局唯一，重复注册即启动失败（4005）；</li>
 *   <li>规则集里出现未注册 id 也是 4005——那说明代码与资源版本脱钩，不能降级放过判断；</li>
 *   <li>规则只返回结构化错误，不抛业务异常。</li>
 * </ul>
 */
package com.arthur.refineloop.capability.runtime.validation.rule;
