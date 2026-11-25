package com.arthur.refineloop.common.constant;

/**
 * 缓存名常量：{@code @Cacheable(cacheNames = CacheNames.XXX)} 的唯一取值来源。
 *
 * <p>与 {@code common.cache.CacheSpec} 一一对应：本类只提供编译期常量（注解要求常量），
 * 容量/TTL/说明由 {@code CacheSpec} 持有，{@code CacheConfig} 据此装配 Caffeine。
 * 新增缓存必须<b>同时</b>登记两处，{@code CacheSpecTest} 会校验两边不漂移。
 *
 * <p>命名规范：小驼峰、语义化、不带前缀（Caffeine 是进程内缓存，不需要命名空间）。
 */
public final class CacheNames {

    /** 股票列表（ts_code → 名称/行业/上市状态）。 */
    public static final String STOCK_LIST = "stockList";

    /** 交易日历（全量）。 */
    public static final String TRADE_CAL = "tradeCal";

    /** 申万行业映射（股票 → 行业）。 */
    public static final String INDUSTRY_MAP = "industryMap";

    /** 受控词表（判断标签词表，按版本号缓存）。 */
    public static final String CONTROLLED_VOCAB = "controlledVocab";

    /** 当前方法版本（单条）。 */
    public static final String METHOD_VERSION = "methodVersion";

    /** 报告内容（report_id → markdown，自然过期）。 */
    public static final String REPORT_CONTENT = "reportContent";

    /** 前端字典快照（枚举/常量组 → 下拉框数据）。 */
    public static final String DICT = "dict";

    /** 运维统计快照（看板聚合结果，短 TTL 抗轮询）。 */
    public static final String OPS_STATS = "opsStats";

    private CacheNames() {
    }
}
