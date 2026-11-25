package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.dto.IngestJobFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 接入失败告警占位监听：本批次只落错误日志，T17 接 Server酱/飞书/邮件。 */
@Component
public class IngestJobFailedListener {

    private static final Logger log = LoggerFactory.getLogger(IngestJobFailedListener.class);

    @EventListener
    public void onIngestJobFailed(IngestJobFailedEvent event) {
        log.error("[告警占位] 接入作业失败 runId={} table={} jobType={} error={}", event.runId(), event.table(),
                event.jobType(), event.errorMessage());
    }
}
