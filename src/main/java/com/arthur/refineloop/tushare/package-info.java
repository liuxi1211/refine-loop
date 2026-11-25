/**
 * 数据接入域：Tushare 客户端、限流、五策略接入编排、数据质量门（全系统唯一外部数据入口）。
 *
 * <p><b>依赖约束</b>：允许依赖 {@code common}、{@code config}、{@code data}；
 * <b>禁止</b>依赖 {@code ops}、{@code web}（由 {@code ArchitectureRulesTest} 校验）。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>41 张表的元数据（fields/幂等键/限流/单位）唯一源是 {@code ingest.registry.TushareTableRegistry}；</li>
 *   <li>落库只经 {@code ingest.service.IngestTableWriter}（单写者），HTTP 调用不包事务；</li>
 *   <li>对外部调用的异常必须分型（网络/限流/参数/业务），限流超限阻塞等待而非失败重试。</li>
 * </ul>
 */
package com.arthur.refineloop.tushare;
