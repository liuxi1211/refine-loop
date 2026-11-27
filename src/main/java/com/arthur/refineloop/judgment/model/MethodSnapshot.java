package com.arthur.refineloop.judgment.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法版本快照：能力详案 3.2 {@code method} 块 + 参与单元清单。
 *
 * <p><b>为什么由调用方传入而不是存档器去查 {@code method_version} 表</b>：真正的版本快照
 * （单元版本组合 + 评分权重 + 词表快照）与流水线启动期的一致性强校验属于 T16；
 * 存档器的职责是「如实冻结本次判断用了什么」，自己编一个版本号会把版本管理做成假象。
 *
 * @param methodVersion 方法版本号（如 {@code M1-v1.0}，落 {@code judgment.method_version} 列）
 * @param school        流派（如 {@code growth_trend}；流派切换 = 新方法版本，详案 1.3）
 * @param units         参与的单元及其判断块摘要（写 {@code judgment_unit_ref}）
 */
public record MethodSnapshot(String methodVersion, String school, List<UnitRef> units) {

    public MethodSnapshot {
        units = units == null ? List.of() : List.copyOf(units);
    }

    /**
     * 单个参与单元。
     *
     * @param unitId        单元 id
     * @param unitVersion   单元版本（如 {@code v1.0}）
     * @param outputSummary 该单元判断块（存 {@code judgment_unit_ref.output_summary}，溯源弹层的数据源）
     */
    public record UnitRef(String unitId, String unitVersion, Map<String, Object> outputSummary) {

        public UnitRef {
            outputSummary = outputSummary == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(outputSummary));
        }
    }
}
