package com.arthur.refineloop.common.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 快捷方法级缓存（Caffeine）：给「临时的、局部的、不值得登记成 {@link CacheSpec} 的」查询用。
 *
 * <p>与 Spring {@code @Cacheable} 的分工：
 * <ul>
 *   <li><b>参考数据（跨模块共享、有明确失效策略）</b> → 用 {@link CacheSpec} + {@code @Cacheable}，
 *       TTL 集中登记，命中率进统一监控；</li>
 *   <li><b>方法级临时缓存</b> → 用本注解，TTL/容量就地声明，不进全局监控清单。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>{@code
 * @LocalCache(name = "suspendCalendar", ttlSeconds = 60, key = "#tradeDate")
 * public List<String> loadSuspendCodes(String tradeDate) { ... }
 *
 * @LocalCacheEvict(name = "suspendCalendar", key = "#tradeDate")
 * public void refreshSuspendCodes(String tradeDate) { ... }
 * }</pre>
 *
 * <p>限制（Spring AOP 代理固有）：同类自调用不生效、只能标注 public 方法、返回 null 会被缓存为 null。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LocalCache {

    /**
     * 缓存名（必须显式写，便于 {@link LocalCacheEvict} 精确失效）。
     * 留空时退化为 {@code 类名#方法名}，仅建议在不可能被外部失效的场景使用。
     */
    String name() default "";

    /** SpEL 键表达式；留空则按方法参数生成（参数含对象时建议显式指定，如 {@code "#query.code"}）。 */
    String key() default "";

    /** 写入后多久过期（秒）。 */
    long ttlSeconds() default 300;

    /** 最大条目数。 */
    long maxSize() default 1000;

    /** 同 key 并发是否只放一个线程穿透（默认是，避免缓存击穿打爆下游）。 */
    boolean sync() default true;
}
