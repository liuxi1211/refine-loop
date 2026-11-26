package com.arthur.refineloop.capability.runtime.validation.rule;

import com.arthur.refineloop.capability.runtime.validation.ValidationError;

import java.util.List;

/**
 * 单元特有校验规则：一条规则实现一个「能力详案 6.5」级别的硬约束。
 *
 * <p>实现要求：
 * <ul>
 *   <li>无状态、纯判断（同输入同输出），可被 4 个并发标的复用；</li>
 *   <li>不抛异常：条件不满足就返回错误（异常只用于配置缺陷）；</li>
 *   <li>错误里写清「字段路径 + 期望值」，它会被原样回喂给模型。</li>
 * </ul>
 */
public interface JudgmentRule {

    /** 规则 id，与 {@code *.rules.json} 中的 {@code id} 一一对应（全局唯一）。 */
    String id();

    /**
     * 执行规则。
     *
     * @param context 判断块 + 事实 + 本条规则的参数
     * @return 结构化错误；空列表表示通过
     */
    List<ValidationError> check(JudgmentRuleContext context);

    /** 便捷构造：错误自动带上本规则 id 作为校验器名。 */
    default ValidationError error(String path, String rule, String message) {
        return new ValidationError(id(), path, rule, message);
    }
}
