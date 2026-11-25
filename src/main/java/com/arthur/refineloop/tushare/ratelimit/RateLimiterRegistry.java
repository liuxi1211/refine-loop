package com.arthur.refineloop.tushare.ratelimit;

import com.arthur.refineloop.config.RateLimitProperties;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 api_name 独立的限流注册表（主控 = 自然分钟窗口，次控 = Guava 平滑）。
 *
 * <p>主控：每个 api 维护自然分钟窗口内的调用计数；超过阈值时 <b>阻塞等待</b>到下一自然分钟
 * （对齐 Tushare 的自然分钟限频，避免 40203 风暴）。
 *
 * <p>次控：对高频接口（每分钟阈值 ≥ {@value #SMOOTHING_MIN_LIMIT}）叠加 Guava
 * {@code RateLimiter(阈值/60)} 做平滑出流，减少自然分钟边界处的瞬时突发。低频接口
 * （如 report_rc 1 次/分钟）不加平滑——自然窗口已经保证间隔，Guava 首许可会带来 1/rate 的不必要等待。
 *
 * <p>计数旁路：每次成功放行后异步记录到 {@code rate_limit_counter}（可配置关闭）。
 *
 * <p>时钟与 sleep 均可注入，单测用假时钟验证“超限阻塞到下一分钟”而不真实等待。
 */
public class RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterRegistry.class);
    private static final long MINUTE_MILLIS = 60_000L;

    /** 低于该阈值的接口不做 Guava 平滑（自然分钟窗口已足够）。 */
    private static final int SMOOTHING_MIN_LIMIT = 120;

    private final RateLimitProperties props;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RateLimitCounterRecorder recorder;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> smoothLimiters = new ConcurrentHashMap<>();

    public RateLimiterRegistry(RateLimitProperties props, Clock clock, Sleeper sleeper,
                               RateLimitCounterRecorder recorder) {
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        this.recorder = recorder;
    }

    /** 获取一个调用许可；超限时阻塞到下一自然分钟。 */
    public void acquire(String apiName) {
        int limit = props.limitOf(apiName);
        if (limit <= 0) {
            return;
        }
        acquireNaturalMinute(apiName, limit);
        maybeSmooth(apiName, limit);
        recorder.record(apiName);
    }

    /** 当前 api 在本自然分钟已放行的次数（观测/测试用）。 */
    public int currentMinuteCount(String apiName) {
        Window w = windows.get(apiName);
        if (w == null) {
            return 0;
        }
        long minute = clock.millis() / MINUTE_MILLIS;
        synchronized (w) {
            return w.minute == minute ? w.count : 0;
        }
    }

    private void acquireNaturalMinute(String apiName, int limit) {
        while (true) {
            long now = clock.millis();
            long minute = Math.floorDiv(now, MINUTE_MILLIS);
            Window window = windows.computeIfAbsent(apiName, k -> new Window());
            synchronized (window) {
                if (window.minute != minute) {
                    window.minute = minute;
                    window.count = 0;
                }
                if (window.count < limit) {
                    window.count++;
                    return;
                }
                long waitMillis = (minute + 1) * MINUTE_MILLIS - now;
                if (waitMillis < 1) {
                    waitMillis = 1;
                }
                log.debug("限流等待 api={} 已用 {}/{}，等待 {}ms 到下一分钟", apiName, window.count, limit, waitMillis);
                sleeper.sleep(waitMillis);
                // 等待期间不释放窗口锁：同一 api 的其他线程按顺序进入下一分钟，避免并发突发
            }
        }
    }

    private void maybeSmooth(String apiName, int limit) {
        if (!props.isSmoothingEnabled() || limit < SMOOTHING_MIN_LIMIT) {
            return;
        }
        RateLimiter limiter = smoothLimiters.computeIfAbsent(apiName,
                k -> RateLimiter.create(limit / 60.0d));
        limiter.acquire();
    }

    private static final class Window {
        long minute = Long.MIN_VALUE;
        int count;
    }
}
