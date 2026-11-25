package com.arthur.refineloop.config;

import com.arthur.refineloop.common.log.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行装配：全局 {@code @Async} 与「提交后台任务」的默认执行器。
 *
 * <p>要点：
 * <ul>
 *   <li><b>虚拟线程优先</b>：本项目异步任务几乎都是 IO 等待（Tushare、LLM），
 *       虚拟线程让「每标的并行」不再需要算线程数；</li>
 *   <li><b>MDC 透传</b>：{@link MdcTaskDecorator} 把 traceId/runId 带到异步线程，
 *       否则异步日志无法与请求/编排串联；</li>
 *   <li><b>拒绝策略 CallerRuns</b>：队列满时由提交线程自己执行，形成天然背压，
 *       比直接抛 {@code RejectedExecutionException} 丢任务安全（盘后批处理不能静默丢表）；</li>
 *   <li><b>优雅停机</b>：等待在途任务 30s，避免 systemd 重启时把正在落库的批次砍断。</li>
 * </ul>
 *
 * <p>注意：{@code @Async} 同样受 Spring 代理限制（同类自调用无效）；需要精确控制并发时，
 * 直接用 {@code common.util.ThreadPools} 创建专用执行器并显式关闭。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(properties.namePrefix());
        executor.setVirtualThreads(Boolean.TRUE.equals(properties.virtual()));
        executor.setCorePoolSize(properties.coreSize());
        executor.setMaxPoolSize(properties.maxSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setKeepAliveSeconds(properties.keepAliveSeconds());
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
