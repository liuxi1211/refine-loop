package com.arthur.refineloop.common.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池工厂：统一命名、Java 21 虚拟线程优先、优雅关闭。
 *
 * <p>为什么强调命名：{@code pool-1-thread-3} 这种名字在日志里等于没有信息；
 * 这里统一 {@code 前缀-序号}，配合 traceId 才能定位到具体阶段。
 *
 * <p>选型建议：
 * <ul>
 *   <li><b>IO 密集/每标的并行调用</b>（LLM、HTTP）→ {@link #virtualThreads(String)}，不要再手动算线程数；</li>
 *   <li><b>CPU 密集</b>（指标计算）→ {@link #fixed(String, int)}，大小 = CPU 核数；</li>
 *   <li><b>定时/延迟</b> → {@link #scheduled(String, int)}（虚拟线程不适合定时调度器）。</li>
 * </ul>
 */
public final class ThreadPools {

    private ThreadPools() {
    }

    /** 每任务一个虚拟线程的执行器（名称带前缀，便于线程 dump 归因）。 */
    public static ExecutorService virtualThreads(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
    }

    public static ThreadFactory virtualThreadFactory(String namePrefix) {
        return Thread.ofVirtual().name(namePrefix + "-", 0).factory();
    }

    /** 固定大小平台线程池（CPU 密集任务）。 */
    public static ExecutorService fixed(String namePrefix, int size) {
        return Executors.newFixedThreadPool(Math.max(1, size), platformThreadFactory(namePrefix));
    }

    /** 定时任务线程池（1~2 个线程足够；调度任务本身必须短，不能阻塞）。 */
    public static ScheduledExecutorService scheduled(String namePrefix, int size) {
        return Executors.newScheduledThreadPool(Math.max(1, size), platformThreadFactory(namePrefix));
    }

    public static ThreadFactory platformThreadFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 优雅关闭：先停止接收，再等待，超时强制中断。 */
    public static void shutdown(ExecutorService executor, Duration grace) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        long graceMillis = grace == null ? 0 : grace.toMillis();
        try {
            if (!executor.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
