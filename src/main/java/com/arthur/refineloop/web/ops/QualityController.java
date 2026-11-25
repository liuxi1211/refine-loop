package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.ops.OpsParams;
import com.arthur.refineloop.ops.OpsViews;
import com.arthur.refineloop.ops.QualityOpsService;
import com.arthur.refineloop.tushare.quality.DataQualityGate;
import com.arthur.refineloop.tushare.quality.QualityCheckLogStore;
import com.arthur.refineloop.tushare.quality.QualityReport;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.web.auth.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step6 quality 模块（契约 §9.3）：S0 质量门最近结果 / 立即执行 / 检查历史。
 *
 * <p>路径统一挂在 {@code /api/ops} 下；执行逻辑全部委托 {@link DataQualityGate}
 * （双层判定 + quality_check_log 落库），检查历史的分页/天数校验与视图映射委托
 * {@link QualityOpsService}；本控制器只做参数校验与转发，不重复实现质量判定。
 */
@RestController
@RequestMapping("/ops/quality")
public class QualityController {

    private final DataQualityGate dataQualityGate;
    private final QualityCheckLogStore checkLogStore;
    private final QualityOpsService qualityOpsService;

    public QualityController(DataQualityGate dataQualityGate, QualityCheckLogStore checkLogStore,
                             QualityOpsService qualityOpsService) {
        this.dataQualityGate = dataQualityGate;
        this.checkLogStore = checkLogStore;
        this.qualityOpsService = qualityOpsService;
    }

    /** GET /api/ops/quality/latest：最近一次 S0 检查结果（无历史 → data:null）。 */
    @GetMapping("/latest")
    public Result<QualityReport> latest() {
        return Result.ok(checkLogStore.latest().orElse(null));
    }

    /** POST /api/ops/quality/run?tradeDate=yyyyMMdd：立即执行 S0 检查并返回报告。 */
    @PostMapping("/run")
    @RequirePermission(Permission.QUALITY_RUN)
    public Result<QualityReport> run(@RequestParam(required = false) String tradeDate) {
        return Result.ok(dataQualityGate.check(OpsParams.optionalDate(tradeDate, "tradeDate"),
                DataQualityGate.TRIGGER_MANUAL));
    }

    /** GET /api/ops/quality/history?days=30&page=1&size=20：近 N 天检查历史（含逐状态计数）。 */
    @GetMapping("/history")
    public Result<OpsViews.QualityHistoryPage> history(
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(qualityOpsService.history(days, PageQuery.of(page, size)));
    }
}
