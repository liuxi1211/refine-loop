package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.judgment.model.AssembledJudgment;
import com.arthur.refineloop.judgment.model.Benchmark;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentBlock;
import com.arthur.refineloop.judgment.model.MethodSnapshot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主判断 payload 组装：把判断块 + 身份 + 方法 + 基准拼成能力详案 3.2 的完整 Schema。
 *
 * <p><b>纯函数</b>：不读库、不取 {@code now()}（时间由调用方传入），因此可以对着固定输入逐字段单测——
 * 判断资产的 JSON 结构是 M3 归因的输入契约，必须能用测试锁死。
 *
 * <p><b>分工</b>：判断块内部字段的形状校验在 {@link JudgmentBlock}（一次解析成强类型）；
 * 本类负责「主判断 = 判断块 + 身份/基准/方法」的<strong>超集</strong>装配与校验——编号、方法版本、
 * 基准、到期日推算、冗余列投影都只在这里产生，避免第二处口径。
 *
 * <p><b>降级块的处理</b>（能力详案 7.3）：框架生成的机械降级块只有
 * {@code direction/confidence/data_gaps/verification_rules}，缺 {@code thesis} 与 {@code key_levels}；
 * {@link JudgmentBlock} 按机械规则补齐（固定文案 + {@code current_side=neutral} + 低置信度），
 * 于是 {@code emitJudgment} 对正常/降级结果走<b>同一条</b>消费路径。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>payload 顶层不出现 null 值（{@link Map#copyOf} 会抛 NPE，且前端契约要求必填字段非空）；</li>
 *   <li>{@code direction.expire_at} 是自然日 {@code yyyy-MM-dd}，不早于 trade_date；</li>
 *   <li>判断块自报的 {@code unit_id@unit_version} 必须出现在本次 {@code method.units} 中（否则溯源断链）。</li>
 * </ul>
 */
public class JudgmentPayloadAssembler {

    /** 主判断的方向枚举说明（原样写进 payload，供人读 Schema）。 */
    static final String DIRECTION_SCALE = "枚举：bullish/bearish/neutral/conditional";

    /** 降级判断的固定文案（委托给 {@link JudgmentBlock}，保证降级文案只有一个事实源）。 */
    public static final String DEGRADED_THESIS = JudgmentBlock.DEGRADED_THESIS;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern TS_CODE = Pattern.compile("^\\d{6}\\.(SH|SZ|BJ)$");
    private static final Pattern TRADE_DATE = Pattern.compile("^\\d{8}$");
    private static final int MAX_METHOD_VERSION_LENGTH = 20;

    /** 组装上下文：编号、重申标记、基准、名称与生成时间。 */
    public record AssemblyContext(String judgmentId,
                                  boolean reaffirm,
                                  Benchmark benchmark,
                                  String stockName,
                                  OffsetDateTime createdAt) {

        public AssemblyContext {
            if (judgmentId == null || judgmentId.isBlank()) {
                throw new IllegalArgumentException("judgmentId 不能为空");
            }
            if (benchmark == null) {
                throw new IllegalArgumentException("benchmark 不能为空（存档时必须固化基准）");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt 不能为空（禁止在组装器内取 now）");
            }
        }
    }

    /** 方向与冗余列的投影：用于「先定方向、再判重申、再组装」。 */
    public record DirectionPlan(String direction, int horizonDays, String expireAt, String confidence) {
    }

    /**
     * 解析并校验方向块（不组装 payload）。
     *
     * <p>单独暴露的原因：{@code is_reaffirm} 依赖「方向 + 到期日」，而重申标记又要写进 payload，
     * 因此必须先出方向、再查历史、最后组装。三步共用一个校验入口，避免两份口径。
     *
     * @param request 存档请求
     * @return 方向投影
     */
    public DirectionPlan planDirection(JudgmentArchiveRequest request) {
        requireIdentity(request);
        JudgmentBlock block = JudgmentBlock.parse(request.judgment().judgmentBlock(),
                request.judgment().degraded());
        String expireAt = resolveExpireAt(request.tradeDate(), block.direction());
        return new DirectionPlan(block.direction().type(), block.direction().horizonDays(),
                expireAt, block.confidence());
    }

    /**
     * 组装完整主判断。
     *
     * @param request 存档请求
     * @param context 编号/基准/名称/生成时间
     * @return 组装结果（payload + 冗余列投影）
     */
    public AssembledJudgment assemble(JudgmentArchiveRequest request, AssemblyContext context) {
        DirectionPlan plan = planDirection(request);
        JudgmentBlock block = JudgmentBlock.parse(request.judgment().judgmentBlock(),
                request.judgment().degraded());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("judgment_id", context.judgmentId());
        payload.put("stock_code", request.tsCode());
        payload.put("stock_name", context.stockName());
        payload.put("pipeline_id", request.pipelineId());
        payload.put("created_at", context.createdAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(ISO_OFFSET));
        payload.put("trade_date", request.tradeDate());
        payload.put("benchmark", benchmarkBlock(context.benchmark()));
        payload.put("report_ref", request.reportRef() == null ? "" : request.reportRef().trim());
        payload.put("degraded", request.judgment().degraded());
        payload.put("method", methodBlock(request.method()));
        payload.put("direction", directionBlock(block.direction(), plan, context.reaffirm()));
        payload.put("key_levels", keyLevelsBlock(block.keyLevels()));
        payload.put("thesis", thesisBlock(block, plan.confidence()));
        payload.put("verification_rules", verificationRulesBlock(block.verificationRules()));
        payload.put("data_gaps", mergeGaps(request.judgment().dataGaps(), block.dataGaps()));
        return new AssembledJudgment(context.judgmentId(), plan.direction(), plan.horizonDays(),
                plan.expireAt(), plan.confidence(), payload);
    }

    // ================================================================ 身份与方法

    private void requireIdentity(JudgmentArchiveRequest request) {
        if (request == null) {
            throw invalid("存档请求不能为空");
        }
        if (request.tsCode() == null || !TS_CODE.matcher(request.tsCode()).matches()) {
            throw invalid("tsCode 必须形如 000001.SZ（当前 " + request.tsCode() + "）");
        }
        if (request.tradeDate() == null || !TRADE_DATE.matcher(request.tradeDate()).matches()) {
            throw invalid("tradeDate 必须为 yyyyMMdd（当前 " + request.tradeDate() + "）");
        }
        if (request.pipelineId() == null || request.pipelineId().isBlank()) {
            throw invalid("pipelineId 不能为空");
        }
        if (request.reportId() <= 0) {
            throw invalid("reportId 必须为正数（降级判断也要有 DEGRADED 占位报告）：" + request.reportId());
        }
        requireMethod(request.method());
        requireBlockBelongsToUnits(request);
    }

    private void requireMethod(MethodSnapshot method) {
        if (method == null) {
            throw invalid("method 快照不能为空（判断必须记录方法版本，否则归因无法分组）");
        }
        String version = Strings.trimToNull(method.methodVersion());
        if (version == null || version.length() > MAX_METHOD_VERSION_LENGTH) {
            throw invalid("method_version 必须为 1~" + MAX_METHOD_VERSION_LENGTH + " 字符（当前 "
                    + method.methodVersion() + "）");
        }
        if (Strings.isBlank(method.school())) {
            throw invalid("method.school 不能为空（流派是方法版本的一部分）");
        }
        if (method.units().isEmpty()) {
            throw invalid("method.units 不能为空：判断必须记录参与单元，否则无法按单元版本归因");
        }
        for (MethodSnapshot.UnitRef unit : method.units()) {
            if (Strings.isBlank(unit.unitId()) || Strings.isBlank(unit.unitVersion())) {
                throw invalid("method.units 存在空 unitId/unitVersion");
            }
        }
    }

    /**
     * 判断块自报的单元身份必须属于本次参与单元清单。
     *
     * <p>要拦的错：把 A 单元的判断块挂到 B 单元的存档请求上（复制粘贴或编排串线），
     * 结果 {@code judgment_unit_ref} 记录的版本与实际产出不符，归因时会把效果算到错误的单元版本上。
     */
    private void requireBlockBelongsToUnits(JudgmentArchiveRequest request) {
        JudgmentBlock.Identity identity = JudgmentBlock.parse(request.judgment().judgmentBlock(),
                request.judgment().degraded()).identity();
        if (!identity.declared()) {
            return;   // 主判断块不一定自报单元（如人工补录），但自报时必须一致
        }
        Set<String> declared = new LinkedHashSet<>();
        request.method().units().forEach(unit -> declared.add(unit.unitId() + "@" + unit.unitVersion()));
        if (!declared.contains(identity.unitId() + "@" + identity.unitVersion())) {
            throw invalid("判断块自报单元 " + identity.unitId() + "@" + identity.unitVersion()
                    + " 不在 method.units 中（" + String.join(", ", declared) + "）");
        }
    }

    private Map<String, Object> methodBlock(MethodSnapshot method) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("method_version", method.methodVersion());
        block.put("school", method.school());
        block.put("units_used", method.units().stream()
                .map(unit -> unit.unitId() + "@" + unit.unitVersion())
                .toList());
        return block;
    }

    private Map<String, Object> benchmarkBlock(Benchmark benchmark) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("index_code", benchmark.indexCode());
        block.put("industry_code", benchmark.industryCode() == null ? "" : benchmark.industryCode());
        return block;
    }

    // ================================================================ 方向与正文

    private Map<String, Object> directionBlock(JudgmentBlock.Direction direction,
                                               DirectionPlan plan, boolean reaffirm) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", plan.direction());
        block.put("scale", DIRECTION_SCALE);
        block.put("horizon_days", plan.horizonDays());
        block.put("expire_at", plan.expireAt());
        block.put("is_reaffirm", reaffirm);
        block.put("target_note", direction.targetNote() == null ? "" : direction.targetNote());
        return block;
    }

    /** 到期日：优先用判断块给的（校验不早于交易日），否则按有效期的自然日推算（详案 3.2 示例口径）。 */
    private String resolveExpireAt(String tradeDate, JudgmentBlock.Direction direction) {
        LocalDate trade = LocalDate.parse(tradeDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
        String declared = direction.declaredExpireAt();
        if (declared == null) {
            return trade.plusDays(direction.horizonDays()).format(ISO_DATE);
        }
        LocalDate expire;
        try {
            expire = LocalDate.parse(declared, ISO_DATE);
        } catch (DateTimeParseException e) {
            throw invalid("direction.expire_at 必须为 yyyy-MM-dd（当前 " + declared + "）");
        }
        if (expire.isBefore(trade)) {
            throw invalid("direction.expire_at 不能早于交易日：" + declared + " < " + tradeDate);
        }
        return expire.format(ISO_DATE);
    }

    private Map<String, Object> keyLevelsBlock(JudgmentBlock.KeyLevels keyLevels) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("support", keyLevels.support());
        block.put("resistance", keyLevels.resistance());
        block.put("stop_loss", keyLevels.stopLoss());
        block.put("invalidation_condition",
                keyLevels.invalidationCondition() == null ? "" : keyLevels.invalidationCondition());
        return block;
    }

    private Map<String, Object> thesisBlock(JudgmentBlock block, String confidence) {
        Map<String, Object> thesis = new LinkedHashMap<>();
        thesis.put("bull_core", block.thesis().bullCore());
        thesis.put("bear_core", block.thesis().bearCore());
        thesis.put("current_side", block.thesis().currentSide());
        // thesis 级置信度缺省时继承整体置信度：两处口径必须一致，否则前端展示与筛选会打架
        thesis.put("confidence", block.thesis().confidence() == null
                ? confidence
                : block.thesis().confidence());
        thesis.put("logic_tags", block.logicTags());
        return thesis;
    }

    private List<Map<String, Object>> verificationRulesBlock(List<JudgmentBlock.VerificationRule> rules) {
        List<Map<String, Object>> normalized = new ArrayList<>(rules.size());
        for (JudgmentBlock.VerificationRule rule : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule_type", rule.ruleType());
            if (rule.target() != null) {
                item.put("target", rule.target());
            }
            item.put("params", rule.params());
            item.put("on_hit", rule.onHit());
            normalized.add(item);
        }
        return normalized;
    }

    private List<String> mergeGaps(List<String> fromRequest, List<String> fromBlock) {
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        fromRequest.forEach(gap -> addGap(gaps, gap));
        fromBlock.forEach(gap -> addGap(gaps, gap));
        return List.copyOf(gaps);
    }

    private void addGap(Set<String> gaps, String gap) {
        String trimmed = Strings.trimToNull(gap);
        // 「无」是提示词约定的占位值，不能变成一条真实缺口
        if (trimmed != null && !"无".equals(trimmed)) {
            gaps.add(trimmed);
        }
    }

    private ApiException invalid(String message) {
        return ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID, message);
    }
}
