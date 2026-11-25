package com.arthur.refineloop.common.cache;

import com.arthur.refineloop.common.constant.CacheNames;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 缓存组件：具名缓存装配、编程式门面、方法级注解与失效。 */
class CacheTest {

    @Test
    void 具名缓存按CacheSpec全部装配且可读写失效() {
        CacheManager manager = new CacheConfig().cacheManager();

        for (CacheSpec spec : CacheSpec.values()) {
            assertThat(manager.getCache(spec.cacheName())).as(spec.cacheName()).isNotNull();
        }

        Cache cache = manager.getCache(CacheNames.TRADE_CAL);
        assertThat(cache).isNotNull();
        cache.put("20261124", "OPEN");
        assertThat(cache.get("20261124", String.class)).isEqualTo("OPEN");
        cache.evict("20261124");
        assertThat(cache.get("20261124")).isNull();
    }

    @Test
    void CacheSpec与CacheNames常量一一对应不漂移() throws IllegalAccessException {
        Set<String> declared = Arrays.stream(CacheNames.class.getFields())
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toSet());
        Set<String> specs = Arrays.stream(CacheSpec.values())
                .map(CacheSpec::cacheName)
                .collect(Collectors.toSet());
        assertThat(declared).containsExactlyInAnyOrderElementsOf(specs);
    }

    @Test
    void CacheTemplate_同key只加载一次且缓存null也是命中() {
        CacheTemplate template = new CacheTemplate(new CacheConfig().cacheManager());
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        String first = template.getOrLoad(CacheSpec.DICT, "k", loader);
        String second = template.getOrLoad(CacheSpec.DICT, "k", loader);
        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
        assertThat(template.contains(CacheSpec.DICT, "k")).isTrue();

        assertThat(template.evict(CacheSpec.DICT, "k")).isTrue();
        String evicted = template.get(CacheSpec.DICT, "k");
        assertThat(evicted).isNull();
        assertThat(template.evict(CacheSpec.DICT, "missing")).isFalse();

        // null 也是合法缓存值：第二次不应回源
        AtomicInteger nullLoads = new AtomicInteger();
        Supplier<String> nullLoader = () -> {
            nullLoads.incrementAndGet();
            return null;
        };
        String nullValue = template.getOrLoad(CacheSpec.DICT, "null-key", nullLoader);
        String nullValueAgain = template.getOrLoad(CacheSpec.DICT, "null-key", nullLoader);
        assertThat(nullValue).isNull();
        assertThat(nullValueAgain).isNull();
        assertThat(nullLoads.get()).isEqualTo(1);

        assertThat(template.stats()).hasSize(CacheSpec.values().length);
        assertThat(template.stats(CacheSpec.DICT).name()).isEqualTo(CacheNames.DICT);
    }

    @Test
    void LocalCache注解缓存方法返回值并按key失效() {
        SampleService target = new SampleService();
        SampleService proxy = proxyOf(target);

        assertThat(proxy.load("a")).isEqualTo("a-1");
        assertThat(proxy.load("a")).isEqualTo("a-1");
        assertThat(target.calls).isEqualTo(1);

        // 不同 key 独立缓存
        assertThat(proxy.load("b")).isEqualTo("b-2");
        assertThat(target.calls).isEqualTo(2);

        // 失效后重新计算
        proxy.evict("a");
        assertThat(proxy.load("a")).isEqualTo("a-3");
        assertThat(target.calls).isEqualTo(3);
    }

    @Test
    void LocalCache_缓存null返回值且syncFalse走非单飞分支() {
        SampleService target = new SampleService();
        SampleService proxy = proxyOf(target);

        assertThat(proxy.loadNull()).isNull();
        assertThat(proxy.loadNull()).isNull();
        assertThat(target.nullCalls).isEqualTo(1);

        assertThat(proxy.loadUnsynced("x")).isEqualTo("x-1");
        assertThat(proxy.loadUnsynced("x")).isEqualTo("x-1");
        assertThat(target.unsyncedCalls).isEqualTo(1);

        proxy.clearAll();
        assertThat(proxy.load("c")).isNotNull();
    }

    @Test
    void 方法级缓存支持按参数位置取值() {
        SampleService target = new SampleService();
        SampleService proxy = proxyOf(target);

        assertThat(proxy.loadByPosition("p")).isEqualTo("p-1");
        assertThat(proxy.loadByPosition("p")).isEqualTo("p-1");
        assertThat(target.calls).isEqualTo(1);
    }

    private static SampleService proxyOf(SampleService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new LocalCacheAspect(new LocalCacheRegistry()));
        return factory.getProxy();
    }

    /** 被代理的目标类（public + 非 final，CGLIB 可代理）。 */
    public static class SampleService {

        int calls;
        int nullCalls;
        int unsyncedCalls;

        @LocalCache(name = "sample", ttlSeconds = 60, maxSize = 10, key = "#id")
        public String load(String id) {
            calls++;
            return id + "-" + calls;
        }

        @LocalCache(name = "sample", ttlSeconds = 60, maxSize = 10, key = "#id")
        public String loadByPosition(String id) {
            calls++;
            return id + "-" + calls;
        }

        @LocalCache(name = "sampleNull", key = "#p0")
        public String loadNull() {
            nullCalls++;
            return null;
        }

        @LocalCache(name = "sampleUnsynced", sync = false)
        public String loadUnsynced(String id) {
            unsyncedCalls++;
            return id + "-" + unsyncedCalls;
        }

        @LocalCacheEvict(name = "sample", key = "#id")
        public void evict(String id) {
            // 仅用于触发失效
        }

        @LocalCacheEvict(name = "sample")
        public void clearAll() {
            // 仅用于触发整个缓存失效
        }
    }
}
