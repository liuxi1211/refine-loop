package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.ops.IngestOpsService;
import com.arthur.refineloop.ops.OpsViews;
import com.arthur.refineloop.ops.PreviewSpec;
import com.arthur.refineloop.ops.TableOpsService;
import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.tushare.ingest.dto.IngestTriggerAck;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.web.auth.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step6 tables 模块（契约 §9.1）：41 表总览/详情/预览/作业时间线/单表增量/全量回填/每日增量开关。
 *
 * <p>路径统一挂在 {@code /api/ops} 下；全部返回 {@link Result} 包装。
 * 业务动作与白名单/参数校验全部下沉到运维读模型域（{@code ops}）：
 * 只读视图走 {@link TableOpsService}，作业动作与作业历史走 {@link IngestOpsService}，
 * 本控制器只做参数接收与转发。
 */
@RestController
@RequestMapping("/ops/tables")
public class TablesController {

    private final TableOpsService tableOpsService;
    private final IngestOpsService ingestOpsService;

    public TablesController(TableOpsService tableOpsService, IngestOpsService ingestOpsService) {
        this.tableOpsService = tableOpsService;
        this.ingestOpsService = ingestOpsService;
    }

    /** GET /api/ops/tables：41 表元数据 + 健康度汇总。 */
    @GetMapping
    public Result<OpsViews.TablesOverview> list() {
        return Result.ok(tableOpsService.overview());
    }

    /** GET /api/ops/tables/{table}：抽屉详情。 */
    @GetMapping("/{table}")
    public Result<OpsViews.TableDetail> detail(@PathVariable String table) {
        return Result.ok(tableOpsService.detail(table));
    }

    /** GET /api/ops/tables/{table}/preview：数据预览（关键列/全部列 + code/日期过滤 + LIMIT 保护）。 */
    @GetMapping("/{table}/preview")
    public Result<OpsViews.PreviewResult> preview(
            @PathVariable String table,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "key") String columnMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "desc") String order) {
        PreviewSpec spec = new PreviewSpec(code, start, end, columnMode, order);
        return Result.ok(tableOpsService.preview(table, spec, PageQuery.of(page, size)));
    }

    /** GET /api/ops/tables/{table}/jobs：该表作业历史时间线（分页，时间倒序）。 */
    @GetMapping("/{table}/jobs")
    public Result<PageResult<IngestJobView>> jobs(
            @PathVariable String table,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ingestOpsService.pageTableJobs(table, PageQuery.of(page, size)));
    }

    /** POST /api/ops/tables/{table}/run：单表增量（jobType=INCR）；编排运行中由 IngestFacadeImpl 抛 409。 */
    @PostMapping("/{table}/run")
    @RequirePermission(Permission.DATA_INGEST)
    public Result<IngestTriggerAck> run(@PathVariable String table) {
        return Result.ok(ingestOpsService.triggerIncr(table));
    }

    /** POST /api/ops/tables/{table}/backfill：异步全量回填（jobType=BACKFILL，start/end 可空）。 */
    @PostMapping("/{table}/backfill")
    @RequirePermission(Permission.DATA_INGEST)
    public Result<IngestTriggerAck> backfill(
            @PathVariable String table,
            @RequestBody(required = false) OpsRequests.BackfillRequest request) {
        String start = request == null ? null : request.start();
        String end = request == null ? null : request.end();
        return Result.ok(ingestOpsService.triggerBackfill(table, start, end));
    }

    /** POST /api/ops/tables/{table}/daily-toggle：每日增量开关（写 sys_config 并留痕操作时间）。 */
    @PostMapping("/{table}/daily-toggle")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<OpsViews.DailyToggleResult> dailyToggle(
            @PathVariable String table,
            @RequestBody OpsRequests.DailyToggleRequest request) {
        return Result.ok(tableOpsService.toggleDaily(table, request == null ? null : request.enabled()));
    }
}
