package com.arthur.refineloop.capability.spi;

/**
 * 能力单元类型。
 *
 * <p>{@code COMPUTE} 是确定性计算（禁止 LLM、禁止远端取数）；
 * {@code INTERPRET} 是 LLM 解读（产出报告与结构化判断块）。
 */
public enum UnitType {
    COMPUTE,
    INTERPRET
}
