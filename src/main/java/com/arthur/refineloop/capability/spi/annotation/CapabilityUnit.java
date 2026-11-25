package com.arthur.refineloop.capability.spi.annotation;

import com.arthur.refineloop.capability.spi.UnitScope;
import com.arthur.refineloop.capability.spi.UnitType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 能力单元元数据注解：Spring Bean 标注后由 {@code UnitRegistry} 启动期扫描注册。
 *
 * <p>为什么注解与接口同名却分属两个包：接口 {@link com.arthur.refineloop.capability.spi.CapabilityUnit}
 * 与注解同名，Java 不允许同包同名类型。注解放在 {@code spi.annotation} 子包，接口默认方法直接读取本注解，
 * 保证元数据只有一份来源。
 *
 * <p>约束：
 * <ul>
 *   <li>{@code id} 必须与 {@code unit_id} 完全一致（资源目录 {@code prompts/{id}/}、{@code schema/{id}-v{x}.json}）；</li>
 *   <li>{@code version} 形如 {@code v1} / {@code v1.0}，只增不改；</li>
 *   <li>{@code scope} 仅对 COMPUTE 有意义；INTERPRET 固定按标的执行。</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CapabilityUnit {

    /** 单元标识，如 {@code calc_valuation_percentile}。 */
    String id();

    /** 单元版本，如 {@code v1.0}。 */
    String version();

    /** 单元类型。 */
    UnitType type();

    /** 执行粒度；默认按标的（interpret 单元无需显式声明）。 */
    UnitScope scope() default UnitScope.PER_STOCK;

    /** 模型档位（fast/strong），仅对 INTERPRET 有意义。 */
    String modelProfile() default "fast";
}
