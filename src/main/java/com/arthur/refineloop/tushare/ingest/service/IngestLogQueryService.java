package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.dto.IngestJobView;
import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.page.PageQuery;

import java.util.List;
import java.util.Optional;

/**
 * ingest_log 只读查询服务（运维 API 作业历史/时间线共用）。
 *
 * <p>分页统一走 {@link PageQuery}（page/size 已校验），结果用 {@code PageResults} 组装。
 * 查询失败（无数据库）由调用方交给全局异常处理转 503。
 */
public interface IngestLogQueryService {

    Optional<IngestJobView> findById(long id);

    PageResult<IngestJobView> page(String status, String table, PageQuery query);

    List<IngestJobView> recentByTable(String table, int limit);

    /** 该表最近一次全量回填（job_type=FULL/BACKFILL）作业。 */
    Optional<IngestJobView> lastFullBackfill(String table);

    /** 该表最近一次作业（任意类型）。 */
    Optional<IngestJobView> latestByTable(String table);
}
