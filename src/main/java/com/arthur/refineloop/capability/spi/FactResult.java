package com.arthur.refineloop.capability.spi;

import com.arthur.refineloop.common.util.JsonUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单元产出的事实（不可变）。
 *
 * <p>payload 是结构化事实的 JSON 视图；单元可以定义自己的强类型 record，
 * 消费方通过 {@link #as(Class)} 还原。{@code schemaVersion} 跟随单元版本冻结，
 * 用于校验链与回放。
 *
 * @param tsCode        股票代码（unit_fact 幂等键的一部分）
 * @param unitId        产出单元的 id
 * @param unitVersion   产出单元的版本
 * @param schemaVersion 事实 Schema 版本
 * @param dataStatus    数据状态（降级是输出的一部分）
 * @param payload       结构化事实，不可变 Map
 * @param notes         降级/缺失原因等说明
 */
public record FactResult(
        String tsCode,
        String unitId,
        String unitVersion,
        String schemaVersion,
        FactDataStatus dataStatus,
        Map<String, Object> payload,
        List<String> notes) {

    public FactResult {
        if (tsCode == null || tsCode.isBlank()) {
            throw new IllegalArgumentException("tsCode 不能为空");
        }
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("unitId 不能为空");
        }
        if (unitVersion == null || unitVersion.isBlank()) {
            throw new IllegalArgumentException("unitVersion 不能为空");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion 不能为空");
        }
        if (dataStatus == null) {
            throw new IllegalArgumentException("dataStatus 不能为空");
        }
        payload = payload == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 将 payload 还原为强类型事实对象。 */
    public <T> T as(Class<T> type) {
        return JsonUtils.parse(JsonUtils.toJson(payload), type);
    }
}
