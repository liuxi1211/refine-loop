package com.arthur.refineloop.capability.spi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单标的事实视图（SPI 通信介质）：两段式生命周期，先并发写入、再 {@link #seal()} 冻结。
 *
 * <p>为什么必须两段式：S2 的 compute 单元并发产出事实，S3 的 interpret 单元只读消费；
 * 冻结后任何写入都抛异常，避免解读阶段看到半成品视图。
 *
 * <p>不变量：
 * <ul>
 *   <li>同一 {@code unitId} 只允许写入一次，重复写入即视为编排缺陷；</li>
 *   <li>事实的 tsCode 必须与视图一致；</li>
 *   <li>seal 后视图不可变，可安全跨线程读取。</li>
 * </ul>
 */
public final class FactBundle {

    private final String tsCode;
    private final String tradeDate;
    private final String runId;
    private final Map<String, FactResult> facts;
    private final boolean sealed;

    private FactBundle(String tsCode, String tradeDate, String runId,
                       Map<String, FactResult> facts, boolean sealed) {
        this.tsCode = tsCode;
        this.tradeDate = tradeDate;
        this.runId = runId;
        this.facts = facts;
        this.sealed = sealed;
    }

    public static Builder builder(String tsCode, String tradeDate, String runId) {
        if (tsCode == null || tsCode.isBlank()) {
            throw new IllegalArgumentException("tsCode 不能为空");
        }
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd：" + tradeDate);
        }
        return new Builder(tsCode, tradeDate, runId == null ? "" : runId);
    }

    public String tsCode() {
        return tsCode;
    }

    public String tradeDate() {
        return tradeDate;
    }

    public String runId() {
        return runId;
    }

    public boolean isSealed() {
        return sealed;
    }

    /** 按 unitId 取事实；不存在返回 null。 */
    public FactResult fact(String unitId) {
        return facts.get(unitId);
    }

    /** 按 unitId 取事实；不存在返回 empty。 */
    public Optional<FactResult> find(String unitId) {
        return Optional.ofNullable(facts.get(unitId));
    }

    /** 按 unitId 取事实并还原为强类型；缺失时抛异常。 */
    public <T> T requireFact(String unitId, Class<T> type) {
        FactResult result = facts.get(unitId);
        if (result == null) {
            throw new IllegalStateException("事实缺失：" + unitId);
        }
        return result.as(type);
    }

    /** 只读视图。 */
    public Map<String, FactResult> all() {
        return facts;
    }

    public int size() {
        return facts.size();
    }

    /**
     * 两段式构建器：{@link #put(FactResult)} 可并发调用，{@link #seal()} 之后拒绝写入。
     */
    public static final class Builder {

        private final String tsCode;
        private final String tradeDate;
        private final String runId;
        private final Map<String, FactResult> facts = new ConcurrentHashMap<>();
        private final AtomicBoolean sealed = new AtomicBoolean(false);

        private Builder(String tsCode, String tradeDate, String runId) {
            this.tsCode = tsCode;
            this.tradeDate = tradeDate;
            this.runId = runId;
        }

        /** 写入一条事实；同一 unitId 重复写入或 seal 后写入都抛异常。 */
        public Builder put(FactResult fact) {
            if (fact == null) {
                throw new IllegalArgumentException("fact 不能为空");
            }
            if (sealed.get()) {
                throw new IllegalStateException("FactBundle 已冻结，禁止写入：" + fact.unitId());
            }
            if (!tsCode.equals(fact.tsCode())) {
                throw new IllegalStateException(
                        "事实 tsCode 与视图不一致：" + fact.tsCode() + " != " + tsCode);
            }
            FactResult previous = facts.putIfAbsent(fact.unitId(), fact);
            if (previous != null) {
                throw new IllegalStateException("重复写入 unitId：" + fact.unitId());
            }
            return this;
        }

        /** 写入批量事实。 */
        public Builder putAll(java.util.Collection<FactResult> batch) {
            if (batch != null) {
                batch.forEach(this::put);
            }
            return this;
        }

        /** 冻结并返回只读视图；重复 seal 返回新的不可变快照。 */
        public FactBundle seal() {
            sealed.set(true);
            return new FactBundle(tsCode, tradeDate, runId, Map.copyOf(facts), true);
        }
    }
}
