package com.arthur.refineloop.capability.spi;

/**
 * 解读单元（L2）：面向单标的事实视图，产出报告与结构化判断块。
 *
 * <p>LlmInvoker、校验链、降级由运行时统一处理，单元实现只负责自己的提示词与输出 Schema。
 */
public interface InterpretUnit extends CapabilityUnit {

    /**
     * 对单标的的事实视图做语义解读。
     *
     * @param facts 该标的的事实视图（已 seal）
     * @return 报告 Markdown + 结构化判断块
     */
    InterpretResult interpret(FactBundle facts);
}
