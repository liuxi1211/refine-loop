package com.arthur.refineloop.web.ops;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.ops.OpsViews;
import com.arthur.refineloop.ops.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step6 stats 模块（契约 §9.4）：GET /api/ops/stats/today 数据总览统计卡数据源。
 */
@RestController
@RequestMapping("/ops/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** GET /api/ops/stats/today：今日作业/调用/表健康度/质量门/最近编排汇总。 */
    @GetMapping("/today")
    public Result<OpsViews.StatsToday> today() {
        return Result.ok(statsService.today());
    }
}
