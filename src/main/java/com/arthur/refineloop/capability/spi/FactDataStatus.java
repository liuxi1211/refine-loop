package com.arthur.refineloop.capability.spi;

/**
 * 事实数据状态：降级是输出的一部分，不是异常。
 *
 * <p>{@code COMPLETE} 数据完整；{@code DEGRADED} 数据不足但产出可用；
 * {@code MISSING} 关键输入缺失，未产出有效事实。
 */
public enum FactDataStatus {
    COMPLETE,
    DEGRADED,
    MISSING
}
