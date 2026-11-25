package com.arthur.refineloop.tushare.ratelimit;

import com.arthur.refineloop.config.RateLimitProperties;
import com.arthur.refineloop.data.mapper.RateLimitCounterMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 限流计数旁路：异步写 {@code rate_limit_counter}（MyBatis Mapper，原子累加）。
 *
 * <p>写库失败只记 debug 日志（不影响主链路：限流决策在内存里，计数表仅供运维观测）。
 * 单线程 executor 保序；SQLite 单写者下与接入作业落库共享写锁由 {@code busy_timeout} 兜底。
 */
public class MybatisRateLimitCounterRecorder implements RateLimitCounterRecorder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MybatisRateLimitCounterRecorder.class);
    private static final DateTimeFormatter MINUTE_SLOT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final RateLimitCounterMapper mapper;
    private final RateLimitProperties props;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-counter");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public MybatisRateLimitCounterRecorder(RateLimitCounterMapper mapper, RateLimitProperties props,
                                           Clock clock) {
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void record(String apiName) {
        if (!props.isCounterEnabled() || closed.get()) {
            return;
        }
        String slot = LocalDateTime.now(clock).format(MINUTE_SLOT);
        executor.execute(() -> {
            try {
                mapper.increment(apiName, slot);
            } catch (Exception e) {
                log.debug("rate_limit_counter 写入失败（忽略）: {}", e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
