package com.arthur.refineloop.common.cache;

import com.arthur.refineloop.common.ApiException;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 编程式缓存门面：注解（{@code @Cacheable} / {@link LocalCache}）表达不了时用它。
 *
 * <p>相对直接用 {@link CacheManager} 的价值：缓存名必须是 {@link CacheSpec} 里登记过的
 * （拼错立刻 400/异常，而不是运行时 NPE）、空值安全、加载失败不写缓存、统一异常语义。
 *
 * <p>典型场景：一次查多张表再拼装结果（注解只能整方法缓存）、按条件决定是否缓存、
 * 需要在业务代码里主动失效。
 */
@Component
public class CacheTemplate {

    private final CacheManager cacheManager;

    public CacheTemplate(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** 读缓存；未命中返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T get(CacheSpec spec, Object key) {
        Cache.ValueWrapper wrapper = requireCache(spec).get(key);
        return wrapper == null ? null : (T) wrapper.get();
    }

    /** 读缓存；未命中用 loader 计算并写入（同 key 并发只会有一个线程真正执行 loader）。 */
    public <T> T getOrLoad(CacheSpec spec, Object key, Supplier<T> loader) {
        Cache cache = requireCache(spec);
        // Spring 的 Cache#get(key, Callable) 内部走 Caffeine 的原子 get(key, mappingFunction)，
        // 天然具备「同 key 单飞」语义；loader 抛异常时不写缓存。
        return cache.get(key, () -> loader.get());
    }

    /** 写入/覆盖。 */
    public void put(CacheSpec spec, Object key, Object value) {
        requireCache(spec).put(key, value);
    }

    /** 失效单个 key；返回是否确实存在。 */
    public boolean evict(CacheSpec spec, Object key) {
        Cache cache = requireCache(spec);
        boolean existed = cache.get(key) != null;
        cache.evict(key);
        return existed;
    }

    /** 清空整个缓存（数据源重建后使用）。 */
    public void clear(CacheSpec spec) {
        requireCache(spec).clear();
    }

    public boolean contains(CacheSpec spec, Object key) {
        return requireCache(spec).get(key) != null;
    }

    /** 全部具名缓存的可观测快照（容量、命中率、淘汰数）。 */
    public List<CacheStatsView> stats() {
        List<CacheStatsView> views = new ArrayList<>();
        for (CacheSpec spec : CacheSpec.values()) {
            Cache cache = cacheManager.getCache(spec.cacheName());
            if (cache == null) {
                continue;
            }
            views.add(toView(spec, cache));
        }
        views.sort(Comparator.comparing(CacheStatsView::name));
        return views;
    }

    public CacheStatsView stats(CacheSpec spec) {
        return toView(spec, requireCache(spec));
    }

    private static CacheStatsView toView(CacheSpec spec, Cache cache) {
        long size = 0;
        long hits = 0;
        long misses = 0;
        long evictions = 0;
        if (cache instanceof CaffeineCache caffeineCache) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
            size = nativeCache.estimatedSize();
            CacheStats stats = nativeCache.stats();
            hits = stats.hitCount();
            misses = stats.missCount();
            evictions = stats.evictionCount();
        }
        return new CacheStatsView(spec.cacheName(), spec.description(),
                size, hits, misses, evictions, spec.ttl() == null ? null : spec.ttl().toSeconds(), spec.maxSize());
    }

    private Cache requireCache(CacheSpec spec) {
        Cache cache = cacheManager.getCache(spec.cacheName());
        if (cache == null) {
            throw ApiException.unavailable("缓存未装配: " + spec.cacheName() + "（请检查 CacheSpec 与 CacheConfig）");
        }
        return cache;
    }

    /** 缓存可观测视图（运维接口返回体）。 */
    public record CacheStatsView(String name,
                                 String description,
                                 long size,
                                 long hitCount,
                                 long missCount,
                                 long evictionCount,
                                 Long ttlSeconds,
                                 long maxSize) {

        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }
    }
}
