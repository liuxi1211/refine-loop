package com.arthur.refineloop.common.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 缓存装配：按 {@link CacheSpec} 一次性建好全部具名缓存（显式集合，不动态创建）。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li><b>SimpleCacheManager + 显式缓存集合</b>：{@code @Cacheable} 写了不存在的缓存名会立刻报错，
 *       而不是「悄悄创建了一个没配 TTL 的缓存」；临时/动态缓存请用 {@link LocalCache} 注解；</li>
 *   <li><b>recordStats()</b>：命中率可观测（{@code GET /api/common/cache}），容量与 TTL 才有调优依据；</li>
 *   <li><b>只在本类 @EnableCaching</b>：入口唯一，避免散落在业务配置里。</li>
 * </ul>
 *
 * <p>注意 Spring 代理的两个经典坑：同类自调用不经过代理（缓存不生效）、{@code private/final} 方法不可代理。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        List<Cache> caches = Arrays.stream(CacheSpec.values())
                .<Cache>map(CacheSpec::toSpringCache)
                .toList();
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(caches);
        // 显式初始化：不依赖容器生命周期，单测里直接 new 也能拿到可用实例（重复初始化是幂等的）
        manager.afterPropertiesSet();
        return manager;
    }
}
