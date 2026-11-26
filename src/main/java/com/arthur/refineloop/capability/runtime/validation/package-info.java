/**
 * L3 判断校验链：责任链顺序固定，链上校验器一律无状态、可单测。
 *
 * <p><b>为什么单独成包</b>：判断可信度靠这一层兜底，它必须能被独立回放与替换，
 * 不能被 LLM 调用细节污染。因此本包只依赖 {@code capability.spi}（事实视图）、
 * {@code common}（工具/异常）与 {@code config}（配置属性），不依赖任何 Mapper 或业务域。
 *
 * <p><b>依赖约束</b>：{@code capability.runtime.validation → capability.spi/common/config}；
 * 禁止依赖 {@code web/ops/tushare/user/pipeline/judgment}，禁止直接触达数据库。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>校验顺序由 {@link com.arthur.refineloop.capability.runtime.validation.ValidationChain}
 *       的字段顺序固化：schema → facts_usage → tag_vocabulary → judgment_rule → report_numbers；</li>
 *   <li>校验器对「模型输出不合法」只返回 {@link com.arthur.refineloop.capability.runtime.validation.ValidationError}，
 *       不抛异常（异常只用于配置缺陷，错误码 4005）；</li>
 *   <li>错误信息必须能直接回喂给模型：说清字段路径、违反的规则与期望值。</li>
 * </ul>
 */
package com.arthur.refineloop.capability.runtime.validation;
