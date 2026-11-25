package com.arthur.refineloop.config;

import com.arthur.refineloop.data.mapper.RateLimitCounterMapper;
import com.arthur.refineloop.tushare.client.TushareClient;
import com.arthur.refineloop.tushare.ratelimit.MybatisRateLimitCounterRecorder;
import com.arthur.refineloop.tushare.ratelimit.RateLimitCounterRecorder;
import com.arthur.refineloop.tushare.ratelimit.RateLimiterRegistry;
import com.arthur.refineloop.tushare.ratelimit.ThreadSleepSleeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * T3 装配：Tushare 客户端 + 限流注册表 + 计数旁路记录器。
 *
 * <p>计数旁路通过 MyBatis Mapper 写 {@code rate_limit_counter}（ORM 改造后不再用 JdbcTemplate）。
 * Clock/Sleeper 均为可注入依赖，便于单测用假时钟验证限流阻塞行为；
 * {@link TushareProperties}/{@link RateLimitProperties} 由 {@code @ConfigurationPropertiesScan} 注册。
 */
@Configuration
public class TushareConfig {

    @Bean
    public Clock appClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public RateLimitCounterRecorder rateLimitCounterRecorder(RateLimitCounterMapper rateLimitCounterMapper,
                                                             RateLimitProperties rateLimitProperties,
                                                             Clock appClock) {
        return new MybatisRateLimitCounterRecorder(rateLimitCounterMapper, rateLimitProperties, appClock);
    }

    @Bean
    public RateLimiterRegistry rateLimiterRegistry(RateLimitProperties rateLimitProperties,
                                                   Clock appClock,
                                                   RateLimitCounterRecorder rateLimitCounterRecorder) {
        return new RateLimiterRegistry(rateLimitProperties, appClock, ThreadSleepSleeper.INSTANCE,
                rateLimitCounterRecorder);
    }

    @Bean
    public TushareClient tushareClient(RestClient.Builder restClientBuilder,
                                       TushareProperties tushareProperties,
                                       RateLimiterRegistry rateLimiterRegistry) {
        return new TushareClient(restClientBuilder, tushareProperties, rateLimiterRegistry);
    }
}
