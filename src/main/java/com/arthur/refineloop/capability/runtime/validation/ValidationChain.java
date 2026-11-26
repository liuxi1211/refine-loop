package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.common.util.Asserts;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 校验链：顺序固定、全部跑完再聚合（不做 fail-fast）。
 *
 * <p>为什么顺序固定（架构 §4.2.5）：从「结构是否成立」到「内容是否可信」逐层收紧——
 * 先 Schema（字段齐全/枚举合法），再事实比对（数字有无编造），再词表与规则，最后正文数字。
 * 结构都不成立时事实比对没有意义，但<b>仍然要全跑</b>：一次把问题说全，重试才有望一次通过。
 *
 * <p>为什么不在这里做「重试」：重试属于模板方法（{@code AbstractInterpretUnit}）的生命周期，
 * 校验链只回答「这份输出能不能进 judgment 表」。
 */
public class ValidationChain {

    /** 输出格式无法解析时统一用的校验器名（不进责任链，属短路错误）。 */
    public static final String PARSE_VALIDATOR = "output_parser";

    private final ValidationValidators validators;

    public ValidationChain(ValidationValidators validators) {
        this.validators = Asserts.notNull(validators, "validators");
    }

    /**
     * 执行整条链。
     *
     * @param context 校验输入
     * @return 聚合结果；{@code passed()} 为 true 才允许 {@code mapOutput}
     */
    public ValidationResult validate(ValidationContext context) {
        Asserts.notNull(context, "context");
        if (context.hasParseError()) {
            return new ValidationResult(List.of(new ValidationError(
                    PARSE_VALIDATOR, "$", "output_format", context.parseError())));
        }
        List<ValidationError> errors = new ArrayList<>();
        for (JudgmentValidator validator : ordered()) {
            List<ValidationError> found = validator.validate(context);
            if (found != null && !found.isEmpty()) {
                errors.addAll(found);
            }
        }
        return new ValidationResult(errors);
    }

    /** 责任链顺序：字段顺序即执行顺序，改动需同步架构文档 §4.2.5。 */
    private List<JudgmentValidator> ordered() {
        return List.of(
                validators.schema(),
                validators.factsUsage(),
                validators.tagVocabulary(),
                validators.judgmentRule(),
                validators.reportNumbers());
    }
}
