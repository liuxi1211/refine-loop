package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.data.entity.JudgmentEntity;
import com.arthur.refineloop.data.entity.JudgmentUnitRefEntity;
import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.judgment.model.AssembledJudgment;
import com.arthur.refineloop.judgment.model.Benchmark;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentArchiveResult;
import com.arthur.refineloop.judgment.model.JudgmentInput;
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
 * 因为 M3 的效果统计需要「降级样本也能被检索、被过滤」，而不是「降级样本不存在」。
 *
 * <p><b>重跑语义</b>（能力详案 3.3 契约 5、架构 §4.3）：同 {@code (trade_date, ts_code, pipeline_id)}
 * 重跑时旧行置 {@code superseded=1 + invalidated_at}，插入新行（编号 seq+1）；
 * 旧行已被归因引用则拒绝（6003）。整段在一个事务里，失败即整体回滚——绝不允许出现
 * 「旧行被置失效、新行没写进去」的中间态（那会让该标的当天没有任何有效判断）。
 *
 * <p><b>并发</b>：S4 是 per-stock 并行，同一天同一流水线的序号必然竞争。策略是
 * 「唯一索引兜底 + 冲突重试」：插入冲突则整段事务回滚重来（重读最新编号）。
 * 不用 {@code synchronized}/锁文件：JVM 重启、多进程、SQLite 单写者下只有数据库约束是可靠的。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>存档成功即 {@code frozen=1, superseded=0}，且内容此后不可改；</li>
 *   <li>任何校验失败都在写库<b>之前</b>抛出 6001（宁可失败，不可存坏数据）；</li>
 *   <li>返回的 {@code supersededJudgmentId} 是本次真实取代的旧编号（没有则为 null）；</li>
 *   <li>{@code direction/horizon_days/expire_at/confidence} 四个冗余列与 payload 同源投影。</li>
 * </ul>
 */
@Slf4j
public class JudgmentArchiveService {

    /** 并发冲突的重试次数（正常业务下 2 次足够；重试仍失败说明有异常高的并发写同一组）。 */
    static final int MAX_ATTEMPTS = 3;

    private static final String GAP_STOCK_NAME = "股票名称缺失（stock_basic 未找到该代码），payload 以代码替代";
    private static final String GAP_INDUSTRY = "申万一级行业归属缺失，benchmark.industry_code 留空（归因需人工补充）";

    private final JudgmentMapper judgmentMapper;
    private final JudgmentUnitRefMapper unitRefMapper;
    private final TushareLookupMapper lookupMapper;
    private final JudgmentIdGenerator idGenerator;
    private final JudgmentPayloadAssembler assembler;
    private final BenchmarkResolver benchmarkResolver;
    private final JudgmentReferenceChecker referenceChecker;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public JudgmentArchiveService(JudgmentMapper judgmentMapper,
                                  JudgmentUnitRefMapper unitRefMapper,
                                  TushareLookupMapper lookupMapper,
                                  JudgmentIdGenerator idGenerator,
                                  JudgmentPayloadAssembler assembler,
                                  BenchmarkResolver benchmarkResolver,
                                  JudgmentReferenceChecker referenceChecker,
                                  TransactionTemplate transactionTemplate,
                                  Clock clock) {
        this.judgmentMapper = judgmentMapper;
        this.unitRefMapper = unitRefMapper;
        this.lookupMapper = lookupMapper;
        this.idGenerator = idGenerator;
        this.assembler = assembler;
        this.benchmarkResolver = benchmarkResolver;
        this.referenceChecker = referenceChecker;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 存档一份主判断。
     *
     * @param request 存档请求
     * @return 编号、被取代的旧编号与重申标记
     * @throws ApiException 6001 载荷不合法 / 6003 已被归因引用 / 6004 序号耗尽 / 6005 并发冲突重试耗尽
     */
    public JudgmentArchiveResult archive(JudgmentArchiveRequest request) {
        // 先校验一次（在开局事务之前失败，避免为了报错而开启写事务）
        assembler.planDirection(request);
        List<String> gaps = new ArrayList<>(request.judgment().dataGaps());
        Benchmark benchmark = benchmarkResolver.resolve(request.tsCode(), request.tradeDate());
        if (benchmark.industryCode() == null) {
            gaps.add(GAP_INDUSTRY);
        }
        String stockName = resolveStockName(request.tsCode(), gaps);

        DuplicateKeyException lastConflict = null;
        RuntimeException lastUniqueViolation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status ->
                        doArchive(request, benchmark, stockName, gaps));
            } catch (DataAccessException e) {
                // sqlite-jdbc 对唯一约束抛 UncategorizedSQLException（SQL state 为 null），
                // 因此按「约束名 + 错误码」判定，而不是只认 DuplicateKeyException
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

    /** 是否为唯一约束冲突（含 SQLite 的 {@code SQLITE_CONSTRAINT_UNIQUE}）。 */
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

    /** 事务内：取代旧行 → 生成编号 → 组装 → 插入判断与单元引用。 */
    private JudgmentArchiveResult doArchive(JudgmentArchiveRequest request, Benchmark benchmark,
                                            String stockName, List<String> gaps) {
        String supersededId = supersedeActive(request);
        String latestId = judgmentMapper.selectLatestJudgmentId(request.tradeDate(), request.pipelineId());
        String judgmentId = idGenerator.next(request.tradeDate(), request.pipelineId(), latestId);

        JudgmentPayloadAssembler.DirectionPlan plan = assembler.planDirection(request);
        // 存续口径按「当前交易日」判定：上一份判断今天还没到期 → 本次为重申
        String asOfDate = DateTimes.formatDate(DateTimes.parseCompactDate(request.tradeDate()));
        boolean reaffirm = judgmentMapper.countPriorActiveSameDirection(
                request.tsCode(), plan.direction(), request.tradeDate(), asOfDate) > 0;

        OffsetDateTime createdAt = OffsetDateTime.now(clock);
        // 行业/名称缺口在组装前补进 data_gaps：payload 里的缺口清单必须与调用方看到的一致
        AssembledJudgment assembled = assembler.assemble(withGaps(request, gaps),
                new JudgmentPayloadAssembler.AssemblyContext(
                        judgmentId, reaffirm, benchmark, stockName, createdAt));

        insertJudgment(assembled, request, createdAt);
        insertUnitRefs(assembled.judgmentId(), request.method(), createdAt);
        log.info("[judgment] 存档完成 id={} code={} tradeDate={} direction={} horizon={} expireAt={} "
                        + "confidence={} degraded={} reaffirm={} superseded={} units={}",
                assembled.judgmentId(), request.tsCode(), request.tradeDate(),
                assembled.direction(), assembled.horizonDays(), assembled.expireAt(),
                assembled.confidence(), request.judgment().degraded(), reaffirm, supersededId,
                request.method().units().size());
        return new JudgmentArchiveResult(assembled.judgmentId(), supersededId, reaffirm,
                assembled.payload());
    }

    /** 同组旧判断置失效；已被归因引用则拒绝（6003）。返回被取代的编号。 */
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
            // 并发下另一线程已置失效：本事务应重试（新行 seq 会不同），交给唯一索引与重试处理
            log.warn("[judgment] 旧判断 {} 标记失效时已被并发修改（affected={}）", active.getJudgmentId(), updated);
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

    private void insertUnitRefs(String judgmentId, MethodSnapshot method, OffsetDateTime createdAt) {
        LocalDateTime now = createdAt.toLocalDateTime();
        for (MethodSnapshot.UnitRef unit : method.units()) {
            JudgmentUnitRefEntity entity = new JudgmentUnitRefEntity();
            entity.setJudgmentId(judgmentId);
            entity.setUnitId(unit.unitId());
            entity.setUnitVersion(unit.unitVersion());
            entity.setOutputSummary(JsonUtils.toJson(unit.outputSummary()));
            entity.setCreatedAt(now);
            unitRefMapper.insert(entity);
        }
    }

    private String resolveStockName(String tsCode, List<String> gaps) {
        String name = lookupMapper.selectStockName(tsCode);
        if (Strings.isBlank(name)) {
            gaps.add(GAP_STOCK_NAME);
            return tsCode;
        }
        return name;
    }

    private List<String> mergeGaps(List<String> extra, java.util.Map<String, Object> payload) {
        List<String> merged = new ArrayList<>(extra);
        Object raw = payload.get("data_gaps");
        if (raw instanceof List<?> existing) {
            existing.stream()
                    .map(String::valueOf)
                    .filter(gap -> !gap.contains(GAP_INDUSTRY) && !gap.contains(GAP_STOCK_NAME))
                    .forEach(merged::add);
        }
        return merged.stream().distinct().toList();
    }

    /** 把补全后的 gaps 写进请求副本（record 不可变，只能重建）。 */
    private JudgmentArchiveRequest withGaps(JudgmentArchiveRequest request, List<String> gaps) {
        return new JudgmentArchiveRequest(request.tsCode(), request.tradeDate(), request.pipelineId(),
                request.reportId(), request.reportRef(), request.method(),
                new JudgmentInput(request.judgment().judgmentBlock(), request.judgment().degraded(), gaps));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知原因";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
