package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.TagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.common.util.Strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ③ {@code logic_tags} 必须落在受控词表内。
 *
 * <p>为什么这条不能省：归因（M3）靠标签聚类统计「哪类逻辑最常出错」，
 * 模型随口造的标签会让统计口径当场失效——一个自由文本字段就毁掉了判断资产的可用性。
 *
 * <p>不变量：只报「越界」与「非字符串」两类错误；缺失/空数组由 Schema 报（避免同一问题重复两条）。
 */
public class TagVocabularyValidator implements JudgmentValidator {

    /** 校验器名。 */
    public static final String NAME = "tag_vocabulary";

    private final TagVocabulary vocabulary;

    public TagVocabularyValidator(TagVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ValidationError> validate(ValidationContext context) {
        Object raw = context.judgmentBlock().get("logic_tags");
        if (!(raw instanceof List<?> tags) || tags.isEmpty()) {
            return List.of();
        }
        List<ValidationError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            checkTag(tags.get(i), "$.logic_tags[" + i + "]", seen, errors);
        }
        return errors;
    }

    private void checkTag(Object item, String path, Set<String> seen, List<ValidationError> errors) {
        String tag = item instanceof String text ? Strings.trimToNull(text) : null;
        if (tag == null) {
            errors.add(new ValidationError(NAME, path, "type", "标签必须是非空字符串"));
            return;
        }
        if (!seen.add(tag)) {
            errors.add(new ValidationError(NAME, path, "duplicate_tag", "标签重复：" + tag));
        }
        if (!vocabulary.contains(tag)) {
            errors.add(new ValidationError(NAME, path, "out_of_vocabulary",
                    "标签越界：" + tag + "（必须取自受控词表，禁止自由发挥）"));
        }
    }
}
