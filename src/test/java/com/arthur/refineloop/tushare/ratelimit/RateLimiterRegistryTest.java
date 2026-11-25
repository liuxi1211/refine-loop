package com.arthur.refineloop.tushare.ratelimit;

import com.arthur.refineloop.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** T3 验收：自然分钟窗口放行、超限阻塞到下一分钟（可注入时钟，不真实等待）。 */
class RateLimiterRegistryTest {

    /** 可推进的假时钟。 */
    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void 阈值内放行超限阻塞到下一自然分钟() {
        // 第 3 次调用应阻塞到下一自然分钟；sleep 时假时钟自动推进，从而无需真实等待
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T02:00:10Z").toEpochMilli());
        List<Long> sleeps = new ArrayList<>();
        Sleeper sleeper = millis -> {
            sleeps.add(millis);
            clock.advance(millis);
        };
        RateLimitProperties props = new RateLimitProperties();
        props.setDefaultLimit(1000);
        props.setSmoothingEnabled(false);
        props.setCounterEnabled(false);
        props.setLimits(Map.of("daily", 2));
        AtomicInteger recorded = new AtomicInteger();
        RateLimiterRegistry registry = new RateLimiterRegistry(props, clock, sleeper, api -> recorded.incrementAndGet());

        registry.acquire("daily");
        registry.acquire("daily");
        assertThat(sleeps).isEmpty();
        assertThat(registry.currentMinuteCount("daily")).isEqualTo(2);

        registry.acquire("daily");   // 超限：等待到下一分钟
        assertThat(sleeps).hasSize(1).first().isEqualTo(50_000L);
        assertThat(registry.currentMinuteCount("daily")).isEqualTo(1);
        assertThat(recorded).hasValue(3);
    }

    @Test
    void 不同api独立计数() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T02:00:10Z").toEpochMilli());
        List<Long> sleeps = new ArrayList<>();
        Sleeper sleeper = millis -> {
            sleeps.add(millis);
            clock.advance(millis);
        };
        RateLimitProperties props = new RateLimitProperties();
        props.setDefaultLimit(5);
        props.setSmoothingEnabled(false);
        props.setCounterEnabled(false);
        props.setLimits(Map.of("daily", 2));
        RateLimiterRegistry registry = new RateLimiterRegistry(props, clock, sleeper, RateLimitCounterRecorder.NOOP);

        registry.acquire("daily");
        registry.acquire("daily");
        registry.acquire("adj_factor");   // 未配置的 api 用 default-limit，不受 daily 影响
        registry.acquire("adj_factor");
        assertThat(sleeps).isEmpty();
        assertThat(registry.currentMinuteCount("adj_factor")).isEqualTo(2);

        registry.acquire("daily");
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void 跨分钟后重新计数() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T02:00:10Z").toEpochMilli());
        List<Long> sleeps = new ArrayList<>();
        Sleeper sleeper = millis -> {
            sleeps.add(millis);
            clock.advance(millis);
        };
        RateLimitProperties props = new RateLimitProperties();
        props.setDefaultLimit(1);
        props.setSmoothingEnabled(false);
        props.setCounterEnabled(false);
        RateLimiterRegistry registry = new RateLimiterRegistry(props, clock, sleeper, RateLimitCounterRecorder.NOOP);

        registry.acquire("report_rc");
        clock.advance(60_000L);           // 手动推进到下一分钟
        registry.acquire("report_rc");
        assertThat(sleeps).isEmpty();
    }
}
