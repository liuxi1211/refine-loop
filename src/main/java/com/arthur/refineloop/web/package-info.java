/**
 * HTTP 适配层：Controller、全局异常出口、健康检查。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code common}、{@code config}、{@code ops}、{@code tushare} 的对外服务；
 * <b>禁止</b>依赖 {@code data} 的持久化细节（entity/mapper/provider/batch/handler），
 * 也禁止在此开启事务（由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>所有端点返回 {@code Result<T>}，路径统一 {@code /api} 前缀（前缀由
 *       {@code config.WebMvcConfig.addPathPrefix} 对整包统一挂载，Controller 内<b>不要</b>手写）；</li>
 *   <li>Controller 只做参数解析 + 校验 + 调服务 + 包装结果，不写业务、不拼文案、不写库；</li>
 *   <li>错误一律抛 {@code ApiException} 或交给 {@code GlobalExceptionHandler}。</li>
 * </ul>
 */
package com.arthur.refineloop.web;
