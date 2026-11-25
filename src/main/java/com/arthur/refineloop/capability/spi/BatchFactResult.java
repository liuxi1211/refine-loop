package com.arthur.refineloop.capability.spi;

import java.util.List;

/**
 * BATCH 单元的批量产出。
 *
 * @param facts 事实列表，不可变
 */
public record BatchFactResult(List<FactResult> facts) {

    public BatchFactResult {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static BatchFactResult empty() {
        return new BatchFactResult(List.of());
    }

    public int size() {
        return facts.size();
    }

    public boolean isEmpty() {
        return facts.isEmpty();
    }
}
