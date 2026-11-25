package com.arthur.refineloop.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.tushare.quality.QualityCheckItem;
import com.arthur.refineloop.tushare.quality.QualityCheckLogStore;
import com.arthur.refineloop.tushare.quality.QualityReport;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Step6 quality 模块的读模型服务（运维读模型域）：检查历史的天数/分页校验 + 视图映射。
 *
 * <p>本类属运维读模型域（{@code com.arthur.refineloop.ops}）：只依赖 tushare/data 域，
 * 向 web 适配层输出 {@link OpsViews}，不反向依赖 web 包。
 *
 * <p>约定（契约 §9.3）：{@code days} 缺省 30 天、上限 3650 天；分页 page/size 走 {@link OpsParams}。
 */
@Service
public class QualityOpsService {

    /** 历史查询默认天数。 */
    private static final int DEFAULT_HISTORY_DAYS = 30;
    /** 历史查询天数上限。 */
    private static final int MAX_HISTORY_DAYS = 3650;

    private final QualityCheckLogStore checkLogStore;
    private final Clock clock;

    public QualityOpsService(QualityCheckLogStore checkLogStore, Clock clock) {
        this.checkLogStore = checkLogStore;
        this.clock = clock;
    }

    /** 近 N 天检查历史（含逐状态计数）；days 为空取默认 30 天。 */
    public OpsViews.QualityHistoryPage history(Integer days, PageQuery query) {
        int effectiveDays = days == null ? DEFAULT_HISTORY_DAYS : days;
        if (effectiveDays < 1 || effectiveDays > MAX_HISTORY_DAYS) {
            throw ApiException.badRequest(
                    "days 必须为 1~" + MAX_HISTORY_DAYS + "（当前 " + effectiveDays + "）");
        }
        LocalDateTime since = LocalDateTime.now(clock).minusDays(effectiveDays);
        long total = checkLogStore.countSince(since);
        List<QualityReport> reports = checkLogStore.pageSince(since, query);
        List<OpsViews.QualityHistoryItem> items = reports.stream()
                .map(report -> new OpsViews.QualityHistoryItem(
                        report.checkId(),
                        report.checkTime(),
                        report.triggerType(),
                        report.result(),
                        report.durationMs(),
                        (int) report.count(QualityCheckItem.PASS),
                        (int) report.count(QualityCheckItem.WARN),
                        (int) report.count(QualityCheckItem.FAIL)))
                .toList();
        return new OpsViews.QualityHistoryPage(items, total, query.page(), query.size());
    }
}
