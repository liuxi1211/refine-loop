/**
 * LLM 调用栈：调用基座（{@code LlmInvoker}）→ 轻量模板（{@code ReportGenerator}）
 * → 完整模板（{@link com.arthur.refineloop.capability.runtime.llm.AbstractInterpretUnit}）。
 *
 * <p><b>唯一性约束</b>：本包是系统中唯一允许直接接触 {@code ChatClient} 的地方；
 * 业务单元只能通过 {@code LlmInvoker}/{@code ReportGenerator}/{@code AbstractInterpretUnit}
 * 间接使用（ArchUnit 校验）。
 *
 * <p><b>分层纪律</b>：进 {@code judgment} 表的调用必须走完整模板（判断要进归因闭环，
 * Schema 校验与版本冻结缺一不可）；报告类任务走轻量模板。
 *
 * <p><b>不变量</b>：完整模板的 {@code interpret()} 为 {@code final}，生命周期不可跳过；
 * 业务单元只实现 promptId/schemaId/buildFactsPayload/mapOutput 四个差异点。
 */
package com.arthur.refineloop.capability.runtime.llm;
