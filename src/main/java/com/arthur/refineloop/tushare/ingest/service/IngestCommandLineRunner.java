package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobResult;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 初始回填/手动接入的 CLI 入口（T4.4 双通道之一）。
 *
 * <pre>
 *   --ingest.daily                                    每日增量编排（同步执行到结束）
 *   --ingest.full=table1,table2                       指定表全量回填（同步逐表执行）
 *   --ingest.table=daily --ingest.mode=incr|full --ingest.start=20150101 --ingest.end=20261231
 * </pre>
 *
 * <p>只有显式传入上述参数才执行；应用正常运行（无参数）不会触发任何接入。
 */
@Component
public class IngestCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestCommandLineRunner.class);

    private final IngestJobService jobService;
    private final IngestOrchestrator orchestrator;

    public IngestCommandLineRunner(IngestJobService jobService, IngestOrchestrator orchestrator) {
        this.jobService = jobService;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("ingest.daily")) {
            log.info("CLI: 开始每日增量编排（同步执行）");
            OrchestrationProgress progress = orchestrator.runDailyBlocking();
            log.info("CLI: 每日编排结束 status={} done={}/{}", progress.status(), progress.done(),
                    progress.total());
            return;
        }
        if (args.containsOption("ingest.full")) {
            List<String> tables = splitTables(args.getOptionValues("ingest.full"));
            log.info("CLI: 开始全量回填 tables={}", tables);
            for (String table : tables) {
                TableDef def = TushareTableRegistry.byTable(table);
                if (def == null) {
                    log.error("CLI: 未知数据表 {}，跳过", table);
                    continue;
                }
                String runId = "CLI" + System.currentTimeMillis();
                IngestJobResult result = jobService.runJob(def, IngestJobService.JOB_BACKFILL, null, null, runId);
                log.info("CLI: 表 {} 回填结束 status={} fetched={} written={}", table, result.status(),
                        result.rowsFetched(), result.rowsWritten());
            }
            return;
        }
        if (args.containsOption("ingest.table")) {
            String table = firstValue(args.getOptionValues("ingest.table"));
            String mode = firstValue(args.getOptionValues("ingest.mode"));
            String start = firstValue(args.getOptionValues("ingest.start"));
            String end = firstValue(args.getOptionValues("ingest.end"));
            TableDef def = TushareTableRegistry.byTable(table);
            if (def == null) {
                log.error("CLI: 未知数据表 {}", table);
                return;
            }
            String jobType = mode != null && mode.equalsIgnoreCase("full")
                    ? IngestJobService.JOB_FULL : IngestJobService.JOB_INCR;
            log.info("CLI: 单表接入 table={} jobType={} start={} end={}", table, jobType, start, end);
            IngestJobResult result = jobService.runJob(def, jobType, start, end, "CLI" + System.currentTimeMillis());
            log.info("CLI: 表 {} 作业结束 status={} fetched={} written={}", table, result.status(),
                    result.rowsFetched(), result.rowsWritten());
        }
    }

    private static List<String> splitTables(List<String> values) {
        List<String> tables = new ArrayList<>();
        if (values == null) {
            return tables;
        }
        for (String value : values) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    tables.add(part.trim());
                }
            }
        }
        return tables;
    }

    private static String firstValue(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
