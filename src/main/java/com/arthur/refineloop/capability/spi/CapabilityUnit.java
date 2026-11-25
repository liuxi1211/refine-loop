package com.arthur.refineloop.capability.spi;

/**
 * 能力单元基础契约：元数据只从 {@code @CapabilityUnit} 注解读取，接口不重复声明。
 *
 * <p>为什么默认实现读注解：单元类只写 {@code @CapabilityUnit(...) implements ComputeUnit}，
 * 元数据只有一份来源；{@code UnitRegistry} 启动期校验注解存在性与合法性。
 *
 * <p>注解与接口同名，无法在同包同时 import，这里用全限定名引用注解。
 */
public interface CapabilityUnit {

    /** 单元标识；未标注 {@code @CapabilityUnit} 时返回 {@code null}，由注册器拒绝。 */
    default String unitId() {
        com.arthur.refineloop.capability.spi.annotation.CapabilityUnit annotation =
                getClass().getAnnotation(com.arthur.refineloop.capability.spi.annotation.CapabilityUnit.class);
        return annotation == null ? null : annotation.id();
    }

    /** 单元版本；未标注注解时返回 {@code null}。 */
    default String version() {
        com.arthur.refineloop.capability.spi.annotation.CapabilityUnit annotation =
                getClass().getAnnotation(com.arthur.refineloop.capability.spi.annotation.CapabilityUnit.class);
        return annotation == null ? null : annotation.version();
    }

    /** 单元类型；未标注注解时返回 {@code null}。 */
    default UnitType type() {
        com.arthur.refineloop.capability.spi.annotation.CapabilityUnit annotation =
                getClass().getAnnotation(com.arthur.refineloop.capability.spi.annotation.CapabilityUnit.class);
        return annotation == null ? null : annotation.type();
    }
}
