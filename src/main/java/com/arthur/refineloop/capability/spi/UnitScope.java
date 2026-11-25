package com.arthur.refineloop.capability.spi;

/**
 * 能力单元执行粒度。
 *
 * <p>{@code BATCH}：一次执行处理整个标的池（M1 全部 compute 单元均为 BATCH）；
 * {@code PER_STOCK}：逐标的执行（interpret 单元）。
 */
public enum UnitScope {
    BATCH,
    PER_STOCK
}
