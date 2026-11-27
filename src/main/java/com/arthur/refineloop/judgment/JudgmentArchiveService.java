package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.data.entity.JudgmentEntity;
import com.arthur.refineloop.data.entity.JudgmentUnitRefEntity;
import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import com.arthur.refineloop.judgment.model.AssembledJudgment;
import com.arthur.refineloop.judgment.model.Benchmark;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentArchiveResult;
import com.arthur.refineloop.judgment.model.JudgmentInput;
import com.arthur.refineloop.judgment.model.MarketSnapshot;
import com.arthur.refineloop.judgment.model.MethodSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断存档器（{@code emit_structured_judgment} 的唯一实现）：校验 → 冻结 → 落库。
 *
 * <p><b>职责</b>：把 {@link JudgmentArchiveRequest} 变成一条冻结的 {@code judgment} 记录 +
 * 每单元一条 {@code judgment_unit_ref}。正常判断与降级判断走<b>同一条</b>路径（不区分来源），
 * 因为效果统计需要「降级样本也能被检索、被过滤」，而不是「降级样本不存在」。
 *
 * <p><b>重跑语义</b>：同 {@code (trade_date, ts_code, pipeline_id)} 重跑时旧行置
 * {@code superseded=1 + invalidated_at}，插入新行（编号 seq+1）；旧行已被归因引用则拒绝（6003）。
 * 整段在一个事务里，失败即整体回滚。
 *
 * <p><b>并发</b>：单机存档在进程内串行化。SQLite DEFERRED 事务先读后写，在 WAL 并发下可能直接
 * BUSY_SNAPSHOT；判断存档只有本地短事务（无 HTTP/LLM/文件 IO），串行比复杂重试更确定。
 * 数据库唯一索引仍保留，作为最终兜底。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>存档成功即 {@code frozen=1, superseded=0}，且内容此后不可改；</li>
 *   <li>任何校验失败都在写库<b>之前</b>抛出（宁可失败，不可存坏数据）；</li>
 *   <li>行情起点与基准快照必须完整，否则拒绝生成无法验证的方向判断；</li>
 *   <li>{@code direction/horizon_days/expire_at/confidence} 四个冗余列与 payload 同源投影。</li>
 * </ul>
 */
@Slf4j
public class JudgmentArchiveService {

    static final int MAX_ATTEMPTS = 3;

    private static final String GAP_STOCK_NAME = "股票名称缺失（stock_basic 未找到该代码），payload 以代码替代";
    private static final String GAP_INDUSTRY = "申万一级行业归属缺失，benchmark.industry_code 留空（归因需人工补充）";

    private final JudgmentMapper judgmentMapper;
    private final JudgmentUnitRefMapper unitRefMapper;
    private final JudgmentArchiveContextResolver contextResolver;
    private final JudgmentIdGenerator idGenerator;
    private final JudgmentPayloadAssembler assembler;
    private final BenchmarkResolver benchmarkResolver;
    private final JudgmentReferenceChecker referenceChecker;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public JudgmentArchiveService(JudgmentMapper judgmentMapper,
                                  JudgmentUnitRefMapper unitRefMapper,
                                  JudgmentArchiveContextResolver contextResolver,
                                  JudgmentIdGenerator idGenerator,
                                  JudgmentPayloadAssembler assembler,
                                  BenchmarkResolver benchmarkResolver,
                                  JudgmentReferenceChecker referenceChecker,
                                  TransactionTemplate transactionTemplate,
                                  Clock clock) {
        this.judgmentMapper = judgmentMapper;
        this.unitRefMapper = unitRefMapper;
        this.contextResolver = contextResolver;
        this.idGenerator = idGenerator;
        this.assembler = assembler;
        this.benchmarkResolver = benchmarkResolver;
        this.referenceChecker = referenceChecker;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public synchronized JudgmentArchiveResult archive(JudgmentArchiveRequest request) {
        JudgmentPayloadAssembler.DirectionPlan plan = assembler.planDirection(request);
        List<String> gaps = new ArrayList<>(request.judgment().dataGaps());
        Benchmark benchmark = benchmarkResolver.resolve(request.tsCode(), request.tradeDate());
        if (benchmark.industryCode() == null) {
            gaps.add(GAP_INDUSTRY);
        }
        String stockName = normalizeStockName(request.tsCode(), gaps);
        MarketSnapshot snapshot = contextResolver.resolveMarketSnapshot(
                request.tsCode(), request.tradeDate(), benchmark);
        ArchiveWork work = new ArchiveWork(request, plan, benchmark, stockName, gaps, snapshot);

        DuplicateKeyException lastConflict = null;
        RuntimeException lastUniqueViolation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> doArchive(work));
            } catch (DataAccessException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                lastUniqueViolation = e;
                if (e instanceof DuplicateKeyException duplicateKey) {
                    lastConflict = duplicateKey;
                }
                log.warn("[judgment] 编号并发冲突，第 {}/{} 次重试 tradeDate={} code={} pipeline={} cause={}",
                        attempt, MAX_ATTEMPTS, request.tradeDate(), request.tsCode(),
                        request.pipelineId(), safeMessage(e));
            }
        }
        throw ApiException.of(JudgmentErrorCode.CONCURRENT_CONFLICT,
                "判断编号并发冲突，重试 " + MAX_ATTEMPTS + " 次仍失败："
                        + safeMessage(lastConflict != null ? lastConflict : lastUniqueViolation));
    }

    private boolean isUniqueViolation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage() == null ? "" : current.getMessage().toUpperCase(Locale.ROOT);
            if (message.contains("UNIQUE CONSTRAINT") || message.contains("SQLITE_CONSTRAINT_UNIQUE")
                    || message.contains("DUPLICATE KEY")) {
                return true;
            }
        }
        return false;
    }

    private JudgmentArchiveResult doArchive(ArchiveWork work) {
        JudgmentArchiveRequest request = work.request();
        String supersededId = supersedeActive(request);
        String latestId = judgmentMapper.selectLatestJudgmentId(request.tradeDate(), request.pipelineId());
        String judgmentId = idGenerator.next(request.tradeDate(), request.pipelineId(), latestId);

        JudgmentPayloadAssembler.DirectionPlan plan = work.plan();
        String asOfDate = DateTimes.formatDate(DateTimes.parseCompactDate(request.tradeDate()));
        boolean reaffirm = judgmentMapper.countPriorActiveSameDirection(
                request.tsCode(), plan.direction(), request.tradeDate(), asOfDate) > 0;

        OffsetDateTime createdAt = OffsetDateTime.now(clock);
        AssembledJudgment assembled = assembler.assemble(withGaps(request, work.gaps()),
                new JudgmentPayloadAssembler.AssemblyContext(
                        judgmentId, reaffirm, work.benchmark(), work.stockName(), work.snapshot(), createdAt),
                plan);

        insertJudgment(assembled, request, createdAt);
        insertUnitRefs(assembled.judgmentId(), request, createdAt.toLocalDateTime());
        log.info("[judgment] 存档完成 id={} code={} tradeDate={} direction={} horizon={} expireAt={} "
                        + "confidence={} degraded={} reaffirm={} superseded={} units={}",
                assembled.judgmentId(), request.tsCode(), request.tradeDate(),
                assembled.direction(), assembled.horizonDays(), assembled.expireAt(),
                assembled.confidence(), request.judgment().degraded(), reaffirm, supersededId,
                request.method().units().size());
        return new JudgmentArchiveResult(assembled.judgmentId(), supersededId, reaffirm,
                assembled.payload());
    }

    private String supersedeActive(JudgmentArchiveRequest request) {
        JudgmentEntity active = judgmentMapper.selectActive(
                request.tsCode(), request.tradeDate(), request.pipelineId());
        if (active == null) {
            return null;
        }
        if (referenceChecker.isReferenced(active.getJudgmentId())) {
            throw ApiException.of(JudgmentErrorCode.REFERENCED_BY_ATTRIBUTION,
                    "判断 " + active.getJudgmentId() + " 已被归因引用，禁止自动重跑覆盖；"
                            + "如需重估请人工标注失效后重新触发");
        }
        int updated = judgmentMapper.markSuperseded(active.getJudgmentId(), DateTimes.now(clock));
        if (updated != 1) {
            log.warn("[judgment] 旧判断 {} 标记失效时已被并发修改（affected={}）",
                    active.getJudgmentId(), updated);
        }
        return active.getJudgmentId();
    }

    private void insertJudgment(AssembledJudgment assembled, JudgmentArchiveRequest request,
                                OffsetDateTime createdAt) {
        JudgmentEntity entity = new JudgmentEntity();
        entity.setJudgmentId(assembled.judgmentId());
        entity.setTradeDate(request.tradeDate());
        entity.setTsCode(request.tsCode());
        entity.setPipelineId(request.pipelineId());
        entity.setRunId(request.runId());
        entity.setReportId(request.reportId());
        entity.setMethodVersion(request.method().methodVersion());
        entity.setDirection(assembled.direction());
        entity.setHorizonDays(assembled.horizonDays());
        entity.setExpireAt(assembled.expireAt());
        entity.setConfidence(assembled.confidence());
        entity.setPayload(JsonUtils.toJson(assembled.payload()));
        entity.setFrozen(1);
        entity.setSuperseded(0);
        entity.setInvalidatedAt(null);
        entity.setCreatedAt(createdAt.toLocalDateTime());
        judgmentMapper.insert(entity);
    }

    private void insertUnitRefs(String judgmentId, JudgmentArchiveRequest request, LocalDateTime createdAt) {
        for (MethodSnapshot.UnitRef unit : request.method().units()) {
            JudgmentUnitRefEntity entity = new JudgmentUnitRefEntity();
            entity.setJudgmentId(judgmentId);
            entity.setUnitId(unit.unitId());
            entity.setUnitVersion(unit.unitVersion());
            entity.setFactId(contextResolver.resolveFactId(request.runId(), unit.unitId(), unit.unitVersion(),
                    request.tsCode(), request.tradeDate()));
            entity.setOutputSummary(JsonUtils.toJson(unit.outputSummary()));
            entity.setCreatedAt(createdAt);
            unitRefMapper.insert(entity);
        }
    }

    private String normalizeStockName(String tsCode, List<String> gaps) {
        String name = contextResolver.resolveStockName(tsCode);
        if (Strings.isBlank(name)) {
            gaps.add(GAP_STOCK_NAME);
            return tsCode;
        }
        return name;
    }

    private JudgmentArchiveRequest withGaps(JudgmentArchiveRequest request, List<String> gaps) {
        return new JudgmentArchiveRequest(request.tsCode(), request.tradeDate(), request.pipelineId(),
                request.runId(), request.reportId(), request.reportRef(), request.method(),
                new JudgmentInput(request.judgment().judgmentBlock(), request.judgment().degraded(), gaps));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知原因";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record ArchiveWork(JudgmentArchiveRequest request,
                               JudgmentPayloadAssembler.DirectionPlan plan,
                               Benchmark benchmark,
                               String stockName,
                               List<String> gaps,
                               MarketSnapshot snapshot) {
        private ArchiveWork {
            gaps = List.copyOf(gaps);
        }
    }
}
