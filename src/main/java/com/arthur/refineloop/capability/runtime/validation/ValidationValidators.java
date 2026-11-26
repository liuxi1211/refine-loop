package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.capability.runtime.validation.validators.FactsUsageValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.JudgmentRuleValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.ReportNumberScanner;
import com.arthur.refineloop.capability.runtime.validation.validators.SchemaValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.TagVocabularyValidator;

/**
 * 五个校验器的显式集合：让「链上有哪些、顺序如何」在类型层面写死。
 *
 * <p>为什么不用 {@code List<JudgmentValidator>}：List 的顺序依赖装配代码与排序注解，
 * 排序一旦被改动，Schema 校验可能跑到事实比对之后，错误信息质量立刻下降。
 * 参数 record 把顺序变成位置，编译期就固定。
 *
 * @param schema        ① JSON Schema 合法性
 * @param factsUsage    ② facts_used 指标名 + 数值 + 单位比对
 * @param tagVocabulary ③ logic_tags 受控词表
 * @param judgmentRule  ④ 单元特有规则集
 * @param reportNumbers ⑤ 报告正文裸数字扫描
 */
public record ValidationValidators(
        SchemaValidator schema,
        FactsUsageValidator factsUsage,
        TagVocabularyValidator tagVocabulary,
        JudgmentRuleValidator judgmentRule,
        ReportNumberScanner reportNumbers) {
}
