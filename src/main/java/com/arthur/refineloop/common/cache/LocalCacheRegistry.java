package com.arthur.refineloop.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link LocalCache} 的缓存实例仓库：按「缓存名」懒创建 Caffeine 实例并复用。
 *
 * <p>同名缓存以<b>首次声明</b>的 TTL/容量为准，后续不同参数只记 warn 不重建——
 * 让「同一缓存名在多个方法上配了不同 TTL」这种隐性问题在日志里可见，而不是随机生效。
 */
@Component
public class LocalCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheRegistry.class);

    private final ConcurrentMap<String, Cache<String, Object>> caches = new ConcurrentHashMap<>();

    public Cache<String, Object> cache(String name, long ttlSeconds, long maxSize) {
        Cache<String, Object> existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        Cache<String, Object> created = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .maximumSize(Math.max(1, maxSize))
                .recordStats()
                .build();
        Cache<String, Object> previous = caches.putIfAbsent(name, created);
        if (previous != null) {
            return previous;
        }
        log.debug("本地方法缓存已创建：name={} ttl={}s maxSize={}", name, ttlSeconds, maxSize);
        return created;
    }

    public Optional<Cache<String, Object>> find(String name) {
        return Optional.ofNullable(caches.get(name));
    }

    /** 失效单个键；缓存或键不存在时返回 false。 */
    public boolean invalidate(String name, String key) {
        Cache<String, Object> cache = caches.get(name);
        if (cache == null) {
            return false;
        }
        boolean existed = cache.getIfPresent(key) != null;
        cache.invalidate(key);
        return existed;
    }

    /** 清空整个缓存。 */
    public void invalidateAll(String name) {
        Cache<String, Object> cache = caches.get(name);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    public Set<String> names() {
        return caches.keySet();
    }

    /** 单测/运维用：清空并重建，避免缓存实例跨用例泄漏。 */
    public void clearAll() {
        caches.values().forEach(Cache::invalidateAll);
        caches.clear();
    }
}
