/**
 * 判断域：主判断的<b>存档</b>（M1 只做存档与查询；M3 在同一模块内扩展验证扫描与归因）。
 *
 * <p><b>职责</b>：把 interpret 单元产出的判断块 + 身份/方法/基准信息，组装成能力详案 3.2 的主判断
 * Schema，按 {@code J-{date}-{pipeline}-{seq}} 编号冻结落库，并把每个参与单元的版本与判断块摘要写入
 * {@code judgment_unit_ref}。降级判断与正常判断走同一条存档路径（{@code emitJudgment} 不区分来源）。
 *
 * <p><b>依赖方向</b>：只依赖 {@code common}、{@code config}（配置属性）、{@code data}。
 * <b>禁止</b>依赖 {@code web} / {@code capability} / {@code pipeline} / {@code report} / {@code version}
 * / {@code ops} / {@code tushare} / {@code user}：判断存档是「数据 + 契约」的事，
 * 入参用 {@code Map<String,Object>} 承载判断块，不 import 任何单元类型——这样单元升版不会牵动存档器。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>存档即冻结：{@code payload} 写入后没有任何 UPDATE 入口，唯一可变列是
 *       {@code superseded} 与 {@code invalidated_at}（由 {@code JudgmentMapper} 的两个专用方法写）；</li>
 *   <li>同 {@code (ts_code, trade_date, pipeline_id)} 至多一行 {@code superseded=0}（DB 生成列 + 唯一索引兜底）；</li>
 *   <li>重跑不覆盖旧判断：旧行置 {@code superseded=1 + invalidated_at}，插入新行（编号 seq+1）；</li>
 *   <li>已被归因引用的判断拒绝自动重跑（M1 预留检查，M3 实化）。</li>
 * </ul>
 */
package com.arthur.refineloop.judgment;
