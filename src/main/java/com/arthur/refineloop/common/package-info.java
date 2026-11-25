/**
 * 公共基础设施：统一响应、错误码、分页、日志、字典、缓存、校验、工具类。
 *
 * <p><b>依赖约束</b>：只允许依赖 JDK / 第三方通用库 / 自身子包；<b>禁止</b>依赖
 * {@code ops}、{@code tushare}、{@code data}、{@code web}、{@code config}
 * （由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>本包不出现 {@code @RestController}（web 适配器归 {@code com.arthur.refineloop.web}）；</li>
 *   <li>不出现业务语义常量（表名、状态码值、业务阈值）；</li>
 *   <li>新增能力必须先在《公共基础设施使用手册》登记。</li>
 * </ul>
 */
package com.arthur.refineloop.common;
