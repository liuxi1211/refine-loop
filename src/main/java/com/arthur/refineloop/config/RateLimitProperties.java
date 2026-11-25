package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Tushare 限流配置（前缀 {@code tushare.ratelimit}，资源文件 ratelimit.yml）。
 *
 * <p>阈值按《Tushare接口对接手册》附录 B 留 10~25% 余量后落配置，全部可调；
 * 未显式配置的 api 用 {@link #defaultLimit}。
 */
@ConfigurationProperties(prefix = "tushare.ratelimit")
public class RateLimitProperties {

    /** 未配置 api 的默认每分钟调用上限。 */
    private int defaultLimit = 60;

    /** 是否启用 Guava 平滑出流（次控）。高频接口才启用，低频接口靠自然分钟窗口已足够。 */
    private boolean smoothingEnabled = true;

    /** 是否异步写 rate_limit_counter 观测表（测试/无库环境可关）。 */
    private boolean counterEnabled = true;

    /** api_name -> 每分钟调用上限。 */
    private Map<String, Integer> limits = new HashMap<>();

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public boolean isSmoothingEnabled() {
        return smoothingEnabled;
    }

    public void setSmoothingEnabled(boolean smoothingEnabled) {
        this.smoothingEnabled = smoothingEnabled;
    }

    public boolean isCounterEnabled() {
        return counterEnabled;
    }

    public void setCounterEnabled(boolean counterEnabled) {
        this.counterEnabled = counterEnabled;
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = limits;
    }

    /** 取某 api 的每分钟上限（未配置回退默认值）。 */
    public int limitOf(String apiName) {
        Integer v = limits == null ? null : limits.get(apiName);
        return v == null || v <= 0 ? defaultLimit : v;
    }
}
