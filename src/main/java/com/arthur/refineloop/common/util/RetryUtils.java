package com.arthur.refineloop.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 重试工具：外部调用（Tushare、LLM）的标准退避重试。
 *
 * <p>设计纪律（与架构 §6.3 一致）：
 * <ul>
 *   <li><b>只重试「可能自愈」的失败</b>：网络抖动、5xx、超时；参数错误（4xx/50101）重试只是浪费配额，
 *       由 {@code retryOn} 显式排除；</li>
 *   <li><b>指数退避 + 抖动</b>：{@code backoff * multiplier^n * (0.5~1.0)}，避免多个线程同时重试；</li>
 *   <li>每次重试记 warn（对方能看到服务在降级），最终成功记 debug（正常路径不刷屏）。</li>
 * </ul>
 *
 * <p>注解式重试（Spring Retry）不在依赖基准清单内，这里用最小实现覆盖需求。
 */
public final class RetryUtils {

    private static final Logger log = LoggerFactory.getLogger(RetryUtils.class);

    private RetryUtils() {
    }

    /** 可抛受检异常的动作。 */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** 无返回值动作。 */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 默认重试策略：任何 RuntimeException 都重试（调用方应传入更精确的 {@code retryOn}）。 */
    public static <T> T retry(String name, int maxAttempts, Duration initialBackoff,
                              ThrowingSupplier<T> action) {
        return retry(name, maxAttempts, initialBackoff, 2.0, exception -> true, action);
    }

    public static <T> T retry(String name,
                              int maxAttempts,
                              Duration initialBackoff,
                              double multiplier,
                              Predicate<Exception> retryOn,
                              ThrowingSupplier<T> action) {
        int attempts = Math.max(1, maxAttempts);
        long backoffMillis = Math.max(0, initialBackoff == null ? 0 : initialBackoff.toMillis());
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.debug("{} 第 {} 次尝试成功", name, attempt);
                }
                return result;
            } catch (Exception e) {
                last = e;
                if (attempt >= attempts || !retryOn.test(e)) {
                    break;
                }
                long sleep = jitter(backoffMillis);
                log.warn("{} 第 {}/{} 次失败（{}），{} ms 后重试", name, attempt, attempts,
                        Strings.truncate(e.toString(), 200), sleep);
                if (!sleep(sleep)) {
                    throw new IllegalStateException(name + " 重试被中断", e);
                }
                backoffMillis = (long) (backoffMillis * Math.max(1.0, multiplier));
            }
        }
        log.warn("{} 重试 {} 次后仍失败：{}", name, attempts, Strings.truncate(String.valueOf(last), 200));
        if (last instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(name + " 执行失败", last);
    }

    public static void retry(String name, int maxAttempts, Duration initialBackoff,
                             ThrowingRunnable action) {
        retry(name, maxAttempts, initialBackoff, 2.0, exception -> true, () -> {
            action.run();
            return null;
        });
    }

    /** 抖动：0.5~1.0 倍，避免重试风暴同步打下游。 */
    private static long jitter(long baseMillis) {
        if (baseMillis <= 0) {
            return 0;
        }
        return (long) (baseMillis * (0.5 + ThreadLocalRandom.current().nextDouble(0.5)));
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
