package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用线程池参数（{@code refine-loop.threads.*}）。
 *
 * <p>由启动类 {@code @ConfigurationPropertiesScan} 注册；装配见 {@link AsyncConfig}。
 * 虚拟线程开启时 {@code core-size/max-size/queue-capacity} 不生效（每任务一个虚拟线程），
 * 但保留配置是为了「将来关掉虚拟线程」时不用再改代码。
 *
 * @param virtual           是否使用虚拟线程（Java 21；IO 密集任务应保持 true）
 * @param coreSize          核心线程数（平台线程模式）
 * @param maxSize           最大线程数（平台线程模式）
 * @param queueCapacity     队列容量（平台线程模式）
 * @param keepAliveSeconds  空闲线程存活秒数
 * @param namePrefix        线程名前缀（日志/线程 dump 归因）
 */
@ConfigurationProperties(prefix = "refine-loop.threads")
public record ThreadPoolProperties(
        Boolean virtual,
        Integer coreSize,
        Integer maxSize,
        Integer queueCapacity,
        Integer keepAliveSeconds,
        String namePrefix) {

    public ThreadPoolProperties {
        virtual = virtual == null ? Boolean.TRUE : virtual;
        coreSize = coreSize == null || coreSize < 1 ? 8 : coreSize;
        maxSize = maxSize == null || maxSize < coreSize ? Math.max(coreSize, 32) : maxSize;
        queueCapacity = queueCapacity == null || queueCapacity < 0 ? 200 : queueCapacity;
        keepAliveSeconds = keepAliveSeconds == null || keepAliveSeconds < 0 ? 60 : keepAliveSeconds;
        namePrefix = namePrefix == null || namePrefix.isBlank() ? "refine-" : namePrefix;
    }
}
