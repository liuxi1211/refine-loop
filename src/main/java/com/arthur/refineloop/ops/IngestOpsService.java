package com.arthur.refineloop.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.tushare.ingest.service.IngestFacade;
import com.arthur.refineloop.tushare.ingest.service.IngestJobService;
import com.arthur.refineloop.tushare.ingest.service.IngestJobStatus;
import com.arthur.refineloop.tushare.ingest.service.IngestLogQueryService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Step6 ingest 模块的读模型服务（运维读模型域）：作业历史筛选/分页、单表动作与表白名单校验。
 *
 * <p>本类属运维读模型域（{@code com.arthur.refineloop.ops}）：状态归一与表名白名单收口在此，
 * web 控制器只做参数接收与转发，不再直连 {@link IngestLogQueryService} 或实现类常量。
 */
@Service
public class IngestOpsService {

    /** 合法状态集合从枚举派生，避免与 {@link IngestJobStatus} 漂移。 */
    private static final Set<String> JOB_STATUSES = Set.copyOf(IngestJobStatus.codes());

    private final IngestFacade ingestFacade;
    private final IngestLogQueryService ingestLogQueryService;

    public IngestOpsService(IngestFacade ingestFacade, IngestLogQueryService ingestLogQueryService) {
        this.ingestFacade = ingestFacade;
        this.ingestLogQueryService = ingestLogQueryService;
    }

    /** 单表增量触发（jobType=INCR；编排运行中由 {@link IngestFacade} 实现抛 409）。 */
    public IngestTriggerAck triggerIncr(String table) {
        OpsParams.requireTable(table);
        return ingestFacade.triggerSingleTable(table, IngestJobService.JOB_INCR, null, null);
    }

    /**
     * 每日编排触发；已有编排运行中 → 409（把 {@code ack.accepted()==false} 翻译成用户可读冲突）。
     *
     * <p>翻译放在领域服务而不是 Controller：HTTP 语义（409）属于适配层关心的事，
     * 而"编排互斥"是运维域的业务规则。
     */
    public IngestTriggerAck triggerDaily() {
        IngestTriggerAck ack = ingestFacade.triggerDaily();
        if (ack != null && ack.accepted()) {
            return ack;
        }
        String message = ack == null || ack.message() == null || ack.message().isBlank()
                ? "已有编排正在运行，请等待结束或查看当前进度"
                : ack.message();
        throw ApiException.conflict(message);
    }

    /** 单表全量回填触发（jobType=BACKFILL，start/end 均可空，yyyyMMdd）。 */
    public IngestTriggerAck triggerBackfill(String table, String start, String end) {
        OpsParams.requireTable(table);
        String normalizedStart = OpsParams.optionalDate(start, "start");
        String normalizedEnd = OpsParams.optionalDate(end, "end");
        OpsParams.requireRangeOrder(normalizedStart, normalizedEnd);
        return ingestFacade.triggerSingleTable(table, IngestJobService.JOB_BACKFILL, normalizedStart,
                normalizedEnd);
    }

    /** ingest_log 作业历史（状态/表名筛选 + 分页）；status 大小写不敏感，表名走白名单。 */
    public PageResult<IngestJobView> pageJobs(String status, String table, PageQuery query) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedTable = null;
        if (table != null && !table.isBlank()) {
            normalizedTable = OpsParams.requireTable(table.trim()).tableName();
        }
        return ingestLogQueryService.page(normalizedStatus, normalizedTable, query);
    }

    /** 单表作业时间线（表名白名单校验 + 分页）。 */
    public PageResult<IngestJobView> pageTableJobs(String table, PageQuery query) {
        OpsParams.requireTable(table);
        return ingestLogQueryService.page(null, table, query);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!JOB_STATUSES.contains(normalized)) {
            throw ApiException.badRequest("status 仅支持 " + String.join("/", IngestJobStatus.codes())
                    + "（当前 " + status + "）");
        }
        return normalized;
    }
}
