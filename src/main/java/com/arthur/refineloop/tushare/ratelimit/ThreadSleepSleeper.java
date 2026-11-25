package com.arthur.refineloop.tushare.ratelimit;

/** 默认 Sleeper：真实线程睡眠。 */
public final class ThreadSleepSleeper implements Sleeper {

    public static final ThreadSleepSleeper INSTANCE = new ThreadSleepSleeper();

    @Override
    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("限流等待被中断", e);
        }
    }
}
