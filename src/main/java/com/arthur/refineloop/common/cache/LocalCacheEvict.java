package com.arthur.refineloop.common.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 失效 {@link LocalCache}：方法成功返回后清除对应缓存项。
 *
 * <p>只在<b>方法正常返回</b>后失效（抛异常说明数据未必改成功，此时清缓存没有意义）。
 * {@code key} 的 SpEL 语境与 {@link LocalCache#key()} 完全一致，可以直接写被改动的业务键。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LocalCacheEvict {

    /** 目标缓存名，必须与 {@link LocalCache#name()} 完全一致。 */
    String name();

    /** SpEL 键表达式；留空表示清空该缓存的全部条目。 */
    String key() default "";
}
