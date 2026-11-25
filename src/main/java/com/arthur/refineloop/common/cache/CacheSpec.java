package com.arthur.refineloop.common.cache;

import com.arthur.refineloop.common.constant.CacheNames;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * 具名缓存登记表：容量、TTL、用途的唯一事实源（{@code CacheConfig} 据此装配 Caffeine）。
 *
 * <p>为什么不把 TTL 写进 {@code @Cacheable}：注解里只有缓存名，TTL 散落在注解上会「同一个缓存
 * 不同方法不同 TTL」，实际行为取决于先命中的那个方法，属于典型的隐蔽 bug。这里集中登记后，
 * 注解只负责「用哪个缓存」，生命周期只有一处定义。
 *
 * <p>容量/TTL 取值依据《技术架构与模块设计》§6.5 本地缓存策略；原则是
 * <b>只缓存参考数据，不缓存业务事实</b>（事实以库表为准，保证可溯源）。
 */
public enum CacheSpec {

    /** 股票列表：全量，1 天；盘后 ingest 完成后主动失效。 */
    STOCK_LIST(CacheNames.STOCK_LIST, Duration.ofDays(1), 8, "股票列表（ts_code → 名称/行业/状态）"),

    /** 交易日历：全量，7 天；每周一刷新。 */
    TRADE_CAL(CacheNames.TRADE_CAL, Duration.ofDays(7), 4, "交易日历（全量）"),

    /** 申万行业映射：全量，1 天；ingest 后失效。 */
    INDUSTRY_MAP(CacheNames.INDUSTRY_MAP, Duration.ofDays(1), 8, "申万行业映射"),

    /** 受控词表：按版本号缓存，无 TTL；版本变更主动失效。 */
    CONTROLLED_VOCAB(CacheNames.CONTROLLED_VOCAB, null, 16, "判断标签受控词表（版本变更主动失效）"),

    /** 当前方法版本：单条，无 TTL；升版主动失效。 */
    METHOD_VERSION(CacheNames.METHOD_VERSION, null, 4, "当前方法版本（升版主动失效）"),

    /** 报告内容：100 条，30 分钟自然过期。 */
    REPORT_CONTENT(CacheNames.REPORT_CONTENT, Duration.ofMinutes(30), 100, "报告 markdown 内容"),

    /** 字典快照：静态数据，12 小时兜底过期；{@code POST /api/common/dicts/refresh} 主动失效。 */
    DICT(CacheNames.DICT, Duration.ofHours(12), 8, "前端字典快照"),

    /** 运维统计：短 TTL（30 秒）抗看板轮询，避免每次轮询都打库。 */
    OPS_STATS(CacheNames.OPS_STATS, Duration.ofSeconds(30), 8, "运维看板统计快照");

    private final String cacheName;
    private final Duration ttl;
    private final long maxSize;
    private final String description;

    CacheSpec(String cacheName, Duration ttl, long maxSize, String description) {
        this.cacheName = cacheName;
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.description = description;
    }

    public String cacheName() {
        return cacheName;
    }

    /** null 表示不过期（只能靠主动失效）。 */
    public Duration ttl() {
        return ttl;
    }

    public long maxSize() {
        return maxSize;
    }

    public String description() {
        return description;
    }

    public static Optional<CacheSpec> byName(String cacheName) {
        return Arrays.stream(values())
                .filter(spec -> spec.cacheName.equals(cacheName))
                .findFirst();
    }

    /** 装配一个 Spring Cache 实例（Caffeine 主体 + Spring 适配层）。 */
    public Cache toSpringCache() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .recordStats();
        if (ttl != null) {
            builder.expireAfterWrite(ttl);
        }
        // allowNullValues=true：允许 @Cacheable 方法返回 null（返回 null 也是合法结果，不该变成异常）
        return new CaffeineCache(cacheName, builder.build(), true);
    }
}
