/**
 * 能力单元 SPI：单元契约与通信介质，只依赖 {@code common}。
 *
 * <p><b>依赖约束</b>：本包不得依赖 {@code capability.runtime}、{@code capability.units} 或 {@code data}。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>SPI 类型一律不可变（record/枚举），实现类不得持有可变共享状态；</li>
 *   <li>注解是单元元数据的唯一来源，接口默认方法直接读取注解，避免双源漂移。</li>
 * </ul>
 */
package com.arthur.refineloop.capability.spi;
