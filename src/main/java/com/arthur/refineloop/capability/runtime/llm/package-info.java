/**
 * LLM 调用基座：双协议客户端、超时/重试、用量记账、提示词加载。
 *
 * <p><b>唯一性约束</b>：本包是系统中唯一允许直接接触 {@code ChatClient} 的地方；
 * 业务单元只能通过 {@code LlmInvoker}/{@code ReportGenerator} 间接使用（ArchUnit 校验）。
 */
package com.arthur.refineloop.capability.runtime.llm;
