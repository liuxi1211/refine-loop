package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.capability.spi.FactBundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 L3 校验的全部输入：模型输出 + 事实底座 + 单元身份。
 *
 * <p>为什么把 {@code factsPayload} 单独传：{@code facts_used} 必须比对的是「喂给模型的那份事实」，
 * 而不是数据库里的全量事实——否则模型按输入引用数字却被判成编造，责任归属就错了。
 *
 * <p>为什么带 {@code parseError}：模型偶尔会漏掉判断块围栏，那是「输出格式错」而非「字段错」，
 * 责任链需要能短路成一条明确错误，而不是让 Schema 校验报一堆 required 缺失。
 *
 * @param facts       标的的事实视图（已 seal）
 * @param factsPayload 喂给模型的事实载荷（{@code buildFactsPayload} 的产物，已摊平规则见 FactMetrics）
 * @param judgmentBlock 待校验的判断块（可能为空 Map，表示模型没给）
 * @param reportMd     报告正文（正文数字扫描的输入）
 * @param parseError   输出解析错误；非空时责任链短路
 * @param unitId       当前单元 id（判断块必须自报同一个 id）
 * @param unitVersion  当前单元版本（版本冻结校验）
 * @param schemaId     判断 Schema 资源名（如 {@code interpret_valuation-v1.json}）
 */
public record ValidationContext(
        FactBundle facts,
        Map<String, Object> factsPayload,
        Map<String, Object> judgmentBlock,
        String reportMd,
        String parseError,
        String unitId,
        String unitVersion,
        String schemaId) {

    public ValidationContext {
        factsPayload = factsPayload == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(factsPayload));
        judgmentBlock = judgmentBlock == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(judgmentBlock));
        reportMd = reportMd == null ? "" : reportMd;
    }

    /** 是否因输出格式无法解析而短路。 */
    public boolean hasParseError() {
        return parseError != null && !parseError.isBlank();
    }
}
