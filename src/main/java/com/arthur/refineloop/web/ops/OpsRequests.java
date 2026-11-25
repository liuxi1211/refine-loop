package com.arthur.refineloop.web.ops;

import java.util.List;

/**
 * 运维 API 的请求体集合（契约 §9；web 适配层入参，响应视图见 {@code ops.OpsViews}）。
 */
public final class OpsRequests {

    private OpsRequests() {
    }

    // ================================================================ tables 模块

    /** POST /api/ops/tables/{table}/daily-toggle 请求体。 */
    public record DailyToggleRequest(Boolean enabled) {
    }

    /** POST /api/ops/tables/{table}/backfill 请求体（start/end 均可空，yyyyMMdd）。 */
    public record BackfillRequest(String start, String end) {
    }

    // ================================================================ ingest 模块

    /** POST /api/ops/ingest/backfill-plan 请求体（mode=standard/all/custom，custom 需 tables）。 */
    public record BackfillPlanRequest(String mode, List<String> tables) {
    }
}
