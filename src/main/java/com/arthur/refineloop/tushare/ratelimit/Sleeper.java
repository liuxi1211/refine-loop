package com.arthur.refineloop.tushare.ratelimit;

/** 可注入的 sleep 抽象（单测用假时钟推进避免真实等待）。 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis);
}
