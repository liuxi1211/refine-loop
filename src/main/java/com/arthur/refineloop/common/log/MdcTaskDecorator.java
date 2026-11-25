package com.arthur.refineloop.common.log;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 线程池上下文装饰器：把提交任务的 traceId/runId 带到执行线程，任务结束还原。
 *
 * <p>没有它的话，虚拟线程池里跑的任务日志会丢掉 traceId，批处理排障基本靠猜。
 * 装配见 {@code config.AsyncConfig}；手写线程池时也可以直接复用：
 * <pre>{@code
 * ThreadPoolTaskExecutor executor = ...;
 * executor.setTaskDecorator(new MdcTaskDecorator());
 * }</pre>
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = TraceContext.capture();
        return () -> {
            Map<String, String> submitter = TraceContext.capture();
            try {
                TraceContext.install(captured);
                runnable.run();
            } finally {
                // 还原执行线程原有上下文，避免线程复用串号
                TraceContext.install(submitter);
            }
        };
    }
}
