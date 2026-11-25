package com.arthur.refineloop.tushare.ratelimit;

/** 调用计数旁路记录器（异步写 rate_limit_counter 观测表）。 */
@FunctionalInterface
public interface RateLimitCounterRecorder {

    void record(String apiName);

    /** 空实现：单测/关闭观测时使用。 */
    RateLimitCounterRecorder NOOP = apiName -> {
    };
}
