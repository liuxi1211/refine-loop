package com.arthur.refineloop.common.error;

/**
 * 统一错误码契约：所有对外可见的失败原因都必须有稳定编码。
 *
 * <p>编码规范（与 {@code Result.code} 同一命名空间）：
 * <ul>
 *   <li>{@code 0}：成功；</li>
 *   <li>{@code 4xx / 5xx}：沿用 HTTP 语义，表示「沟通层」失败（参数、不存在、冲突、服务不可用等），
 *       前端可据此直接给出通用交互，不需要认识具体业务；</li>
 *   <li>{@code 1xxx}：通用业务失败（校验、重复、数据未就绪…）；</li>
 *   <li>{@code 2xxx} 数据接入 · {@code 3xxx} 能力单元 · {@code 4xxx} 流水线 · {@code 5xxx} 判断/报告 ·
 *       {@code 6xxx} AI 调用 · {@code 7xxx} 调度与任务（各域按需新增枚举实现本接口）。</li>
 * </ul>
 *
 * <p>实现约定：实现类必须是枚举（保证编码唯一、可遍历、可自文档化）；同一域内编码不得复用。
 * 用户可读文案放 {@link #message()}，拼接上下文（表名、参数值等）由 {@code ApiException} 的
 * 自定义消息承担，不要把动态内容写进枚举。
 */
public interface ErrorCode {

    /** 稳定错误码。 */
    int code();

    /** 默认用户可读提示（中文，面向最终用户，不暴露内部实现细节）。 */
    String message();

    /** 日志/排查用描述：{@code [code] message}。 */
    default String describe() {
        return "[" + code() + "] " + message();
    }
}
