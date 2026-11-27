package com.arthur.refineloop.tushare.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 接入作业的启动恢复：把上次进程遗留的异步作业转成可重试的 FAILED。
 *
 * <p>依赖方向：仅依赖同域 {@link IngestLogService}。单表异步队列不持久化，JVM 重启后不能假装仍在运行；
 * 必须在 Web 请求与新编排开始前完成状态收敛，避免永久 RUNNING 与质量门死锁。
 * 不变量：只处理 RUNNING/PENDING，不改写 SUCCESS/FAILED/SKIPPED 的历史证据。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRecoveryRunner.class);

    private final IngestLogService ingestLogService;

    public IngestRecoveryRunner(IngestLogService ingestLogService) {
        this.ingestLogService = ingestLogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = ingestLogService.recoverInterruptedJobs();
        if (recovered > 0) {
            log.warn("[ingest] 启动恢复：{} 个上次进程遗留作业已标 FAILED，请核对部分写入后重试", recovered);
        } else {
            log.debug("[ingest] 启动恢复：无遗留运行作业");
        }
    }
}
