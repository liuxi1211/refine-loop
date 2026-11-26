package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationResult;
import com.arthur.refineloop.capability.spi.DegradedJudgment;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.InterpretUnit;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整模板：判断生产类 LLM 调用的全部生命周期，写一次、子类不得绕过。
 *
 * <p><b>职责</b>：加载版本化 prompt → 组装事实载荷 → 经 {@link LlmInvoker} 调用 →
 * 解析输出 → 跑 L3 校验链 → 带错重试（≤2 次）→ 重试耗尽或调用失败则生成机械降级判断。
 *
 * <p><b>依赖方向</b>：{@code units → runtime(llm) → spi}。本类只经 {@code LlmInvoker} 触达
 * 模型（不直接持有 {@code ChatClient}），只经 {@code ValidationChain} 判定输出，
 * 因此单元实现里不会出现任何重试/降级/版本处理代码。
 *
 * <p><b>为什么用模板方法而不是组合</b>（ADR-10）：生命周期严格有序且不可跳步
 * （校验失败必须带错重试、重试耗尽必须降级），{@code final} 从语言层禁止子类跳过骨架，
 * 策略模式做不到这种强制。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>{@link #interpret(FactBundle)} 为 {@code final}，唯一出口；</li>
 *   <li>子类只提供四个差异点：{@link #promptId()}、{@link #schemaId()}、
 *       {@link #buildFactsPayload(FactBundle)}、{@link #mapOutput(UnitRawOutput, FactBundle)}；</li>
 *   <li>单元注解、promptId、schemaId 必须自洽（id 一致 + 主版本一致），否则 4002；</li>
 *   <li>任何失败路径都产出 {@code degraded=true} 的判断块，绝不向上抛业务异常
 *       （单标的失败不能中断整条流水线）。</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractInterpretUnit implements InterpretUnit {

    /** 校验失败后的最大重试次数（共 1 + 2 次调用）：再多也无法显著提升通过率，只是烧钱。 */
    public static final int MAX_RETRY = 2;

    /** 首次调用时回喂变量的占位值（提示词里说明「无」表示首次输出）。 */
    static final String NO_FEEDBACK = "无";

    /** 降级时用自然日近似有效期的天数（交易日历不可用时的兜底）。 */
    private static final int FALLBACK_EXPIRE_DAYS = 7;

    private static final String VAR_FACTS = "facts_json";
    private static final String VAR_FEEDBACK = "validation_feedback";

    /** 用户消息固定为「按系统提示输出」，业务语义全部在版本化 prompt 里。 */
    private static final String USER_PROMPT =
            "请严格按系统提示输出：先给 Markdown 报告正文，末尾给且只给一个 ```json 判断块。";

    private final LlmInvoker llmInvoker;
    private final PromptTemplateLoader promptLoader;
    private final ValidationChain validationChain;
    private final TradingDayCalendar tradingDayCalendar;
    private volatile UnitMetadata metadata;

    protected AbstractInterpretUnit(LlmInvoker llmInvoker,
                                    PromptTemplateLoader promptLoader,
                                    ValidationChain validationChain,
                                    TradingDayCalendar tradingDayCalendar) {
        this.llmInvoker = llmInvoker;
        this.promptLoader = promptLoader;
        this.validationChain = validationChain;
        this.tradingDayCalendar = tradingDayCalendar;
    }

    // ================================================================ 差异点（子类实现）

    /** 差异点 1a：提示词 id，约定与 {@code unitId} 相同（{@code prompts/{id}/{version}.md}）。 */
    protected abstract String promptId();

    /** 差异点 1b：判断 Schema 资源名，约定 {@code {unitId}-v{主版本}.json}。 */
    protected abstract String schemaId();

    /** 差异点 2：喂给模型的事实载荷（裁剪后的事实，禁止喂原始数据）。 */
    protected abstract Map<String, Object> buildFactsPayload(FactBundle facts);

    /** 差异点 3：把已通过校验的原始输出映射为强类型结果。 */
    protected abstract InterpretResult mapOutput(UnitRawOutput raw, FactBundle facts);

    // ================================================================ 模板方法（不可重写）

    /**
     * 执行一次完整解读。
     *
     * @param facts 单标的的事实视图（已 seal）
     * @return 正常结果或机械降级结果；{@code degraded=true} 表示降级
     */
    @Override
    public final InterpretResult interpret(FactBundle facts) {
        requireUsableFacts(facts);
        UnitMetadata unit = requireMetadata();
        Map<String, Object> payload = requirePayload(facts);
        // 提示词与判断 Schema 同按「主版本」冻结：v1.0/v1.1 共用 prompts/{id}/v1.md 与 {id}-v1.json，
        // 补丁版本只改实现不改资源，避免同一指令集出现多份副本（版本溯源仍由 annotation.version 记录）
        String template = promptLoader.load(promptId(), majorVersion(unit.version()));
        String feedback = NO_FEEDBACK;
        ValidationResult lastResult = ValidationResult.pass();

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            UnitRawOutput raw = callOnce(template, payload, feedback, facts, unit);
            if (raw == null) {
                return degrade(facts, unit, "LLM 调用失败（已按调用基座策略重试）");
            }
            ValidationResult result = validationChain.validate(contextOf(facts, payload, raw, unit));
            if (result.passed()) {
                return requireMapped(raw, facts, unit);
            }
            lastResult = result;
            feedback = result.feedback();
            log.warn("[interpret] L3 校验未通过 unit={} stock={} attempt={}/{} errors={} first={}",
                    unit.unitId(), facts.tsCode(), attempt + 1, MAX_RETRY + 1, result.size(), result.summary());
        }
        return degrade(facts, unit, "L3 校验重试耗尽：" + lastResult.summary());
    }

    // ================================================================ 内部步骤

    /** 事实视图是外部传入的，缺失/未冻结属调用方缺陷（不是本标的降级）。 */
    private void requireUsableFacts(FactBundle facts) {
        if (facts == null) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT, "事实视图不能为空");
        }
        if (!facts.isSealed()) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "事实视图必须已 seal（两段式生命周期：构建完成后再交给解读单元）");
        }
    }

    /** 事实载荷缺失 = 单元实现缺陷（拿到模型空输入等于白烧一次调用）。 */
    private Map<String, Object> requirePayload(FactBundle facts) {
        Map<String, Object> payload = buildFactsPayload(facts);
        if (payload == null || payload.isEmpty()) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "buildFactsPayload 返回空：" + unitId() + "（单元实现缺陷，禁止向模型喂空事实）");
        }
        return payload;
    }

    private ValidationContext contextOf(FactBundle facts, Map<String, Object> payload,
                                        UnitRawOutput raw, UnitMetadata unit) {
        return new ValidationContext(facts, payload, raw.judgmentBlock(), raw.reportMd(),
                raw.judgmentParseError(), unit.unitId(), unit.version(), schemaId());
    }

    /** 调一次模型；调用失败只记日志并返回 null（由调用方转降级，不再重试）。 */
    private UnitRawOutput callOnce(String template, Map<String, Object> payload, String feedback,
                                   FactBundle facts, UnitMetadata unit) {
        Map<String, Object> variables = new HashMap<>(4);
        variables.put(VAR_FACTS, JsonUtils.toPrettyJson(payload));
        variables.put(VAR_FEEDBACK, feedback);
        String prompt = promptLoader.render(template, variables);
        try {
            LlmCallResult call = llmInvoker.invoke(new LlmCallSpec(
                    unit.modelProfile(), unit.unitId(), facts.runId(), prompt, USER_PROMPT, null, null));
            return InterpretOutputParser.parse(call.content());
        } catch (ApiException e) {
            log.warn("[interpret] LLM 调用失败，转降级 unit={} stock={} code={} msg={}",
                    unit.unitId(), facts.tsCode(), e.getCode(), e.getMessage());
            return null;
        }
    }

    /** 子类映射结果为空 = 实现缺陷（4002），不能当成降级悄悄吞掉。 */
    private InterpretResult requireMapped(UnitRawOutput raw, FactBundle facts, UnitMetadata unit) {
        InterpretResult mapped = mapOutput(raw, facts);
        if (mapped == null) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "mapOutput 返回空：" + unit.unitId() + "（单元实现缺陷）");
        }
        log.debug("[interpret] 解读成功 unit={} stock={} degraded={} reportChars={}",
                unit.unitId(), facts.tsCode(), mapped.degraded(), mapped.reportMd().length());
        return mapped;
    }

    /**
     * 生成机械降级判断：有效期按 5 个交易日计算，日历不可用时退回自然日近似并注明。
     */
    private InterpretResult degrade(FactBundle facts, UnitMetadata unit, String reason) {
        List<String> reasons = new ArrayList<>(2);
        reasons.add(reason);
        String expireAt = expireDate(facts, reasons);
        log.warn("[interpret] 降级 unit={} stock={} expireAt={} reason={}",
                unit.unitId(), facts.tsCode(), expireAt, reason);
        return InterpretResult.degraded(new DegradedJudgment(
                unit.unitId(), unit.version(), facts.tsCode(), facts.tradeDate(), expireAt, reasons));
    }

    private String expireDate(FactBundle facts, List<String> reasons) {
        String tradeDate = facts.tradeDate();
        try {
            String expireCompact = tradingDayCalendar.plusTradingDays(
                    tradeDate, InterpretResult.DEGRADED_HORIZON_TRADING_DAYS);
            return DateTimes.formatDate(DateTimes.parseCompactDate(expireCompact));
        } catch (ApiException e) {
            LocalDate fallback = DateTimes.parseCompactDate(tradeDate).plusDays(FALLBACK_EXPIRE_DAYS);
            String formatted = DateTimes.formatDate(fallback);
            reasons.add("交易日历不可用，降级判断有效期按自然日近似（" + formatted + "）：" + e.getMessage());
            log.warn("[interpret] 交易日历不可用，降级有效期按自然日近似 tradeDate={} expireAt={}",
                    tradeDate, formatted);
            return formatted;
        }
    }

    // ================================================================ 元数据自洽校验

    /**
     * 校验单元注解与资源命名约定（启动后首次调用即触发，属配置缺陷不降级）。
     */
    private UnitMetadata requireMetadata() {
        UnitMetadata cached = metadata;
        if (cached != null) {
            return cached;
        }
        UnitMetadata resolved = resolveMetadata();
        metadata = resolved;
        return resolved;
    }

    private UnitMetadata resolveMetadata() {
        Class<?> unitClass = ClassUtils.getUserClass(getClass());
        CapabilityUnit annotation = unitClass.getAnnotation(CapabilityUnit.class);
        if (annotation == null) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "缺少 @CapabilityUnit 注解（解读单元必须由注解声明元数据）：" + unitClass.getName());
        }
        if (annotation.type() != UnitType.INTERPRET) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "AbstractInterpretUnit 只接受 INTERPRET 类型：" + annotation.id()
                            + "（当前 " + annotation.type() + "）");
        }
        String expectedPromptId = annotation.id();
        if (!expectedPromptId.equals(promptId())) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "promptId 必须等于 unitId：" + promptId() + " != " + expectedPromptId);
        }
        String expectedSchemaId = expectedPromptId + "-" + majorVersion(annotation.version()) + ".json";
        if (!expectedSchemaId.equals(schemaId())) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "schemaId 必须为 " + expectedSchemaId + "（当前 " + schemaId() + "）");
        }
        return new UnitMetadata(annotation.id(), annotation.version(), annotation.modelProfile());
    }

    /** {@code v1.0 → v1}：Schema 资源按主版本冻结，补丁版本不换 Schema 文件。 */
    private String majorVersion(String version) {
        String trimmed = Strings.trimToEmpty(version);
        if (!trimmed.startsWith("v") || trimmed.length() < 2) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT, "单元版本必须形如 v1 / v1.0：" + version);
        }
        String withoutPrefix = trimmed.substring(1);
        int dot = withoutPrefix.indexOf('.');
        return "v" + (dot < 0 ? withoutPrefix : withoutPrefix.substring(0, dot));
    }

    /** 单元元数据快照：注解是唯一来源，避免与接口默认方法双源漂移。 */
    private record UnitMetadata(String unitId, String version, String modelProfile) {
    }
}
