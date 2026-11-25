package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.ops.IngestOpsService;
import com.arthur.refineloop.tushare.ingest.dto.BackfillPlan;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.tushare.ingest.dto.OrchestrationProgress;
import com.arthur.refineloop.tushare.ingest.service.IngestFacade;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.web.auth.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step6 ingest 模块（契约 §9.2）：每日编排触发/编排进度/作业历史/失败重试/初始回填向导。
 *
 * <p>路径统一挂在 {@code /api/ops} 下；编排与重试动作全部注入 {@link IngestFacade}；
 * 作业历史的状态归一与表名白名单校验收口在 {@link IngestOpsService}，本控制器只做参数接收与转发。
 */
@RestController
@RequestMapping("/ops/ingest")
public class IngestController {

    private final IngestFacade ingestFacade;
    private final IngestOpsService ingestOpsService;

    public IngestController(IngestFacade ingestFacade, IngestOpsService ingestOpsService) {
        this.ingestFacade = ingestFacade;
        this.ingestOpsService = ingestOpsService;
    }

    /** POST /api/ops/ingest/daily：一键每日增量编排；已有编排 RUNNING → 409（由 ops 服务翻译）。 */
    @PostMapping("/daily")
    @RequirePermission(Permission.DATA_INGEST)
    public Result<IngestTriggerAck> triggerDaily() {
        return Result.ok(ingestOpsService.triggerDaily());
    }

    /** GET /api/ops/ingest/orchestrations/current：当前/最近一次编排的分层进度。 */
    @GetMapping("/orchestrations/current")
    public Result<OrchestrationProgress> currentOrchestration() {
        return Result.ok(ingestFacade.currentOrchestration());
    }

    /** GET /api/ops/ingest/jobs?status=&table=&page=&size=：ingest_log 作业历史（筛选 + 分页）。 */
    @GetMapping("/jobs")
    public Result<PageResult<IngestJobView>> jobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String table,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ingestOpsService.pageJobs(status, table, PageQuery.of(page, size)));
    }

    /** POST /api/ops/ingest/jobs/{id}/retry：仅 FAILED 作业可重试（校验在 IngestFacadeImpl）。 */
    @PostMapping("/jobs/{id}/retry")
    @RequirePermission(Permission.DATA_INGEST)
    public Result<IngestTriggerAck> retry(@PathVariable long id) {
        return Result.ok(ingestFacade.retryJob(id));
    }

    /** POST /api/ops/ingest/backfill-plan：初始回填向导（只生成分晚计划，不自动执行）。 */
    @PostMapping("/backfill-plan")
    @RequirePermission(Permission.DATA_INGEST)
    public Result<BackfillPlan> backfillPlan(
            @RequestBody(required = false) OpsRequests.BackfillPlanRequest request) {
        String mode = request == null ? null : request.mode();
        List<String> tables = request == null ? null : request.tables();
        return Result.ok(ingestFacade.createBackfillPlan(mode, tables));
    }
}
