package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.TagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TagVocabularyValidator：logic_tags 受控词表")
class TagVocabularyValidatorTest {

    private final TagVocabularyValidator validator = new TagVocabularyValidator(
            TagVocabulary.of(List.of("估值处于历史低位", "业绩改善", "趋势确立")));

    @Test
    @DisplayName("全部命中词表通过")
    void acceptsKnownTags() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("logic_tags", new ArrayList<>(List.of("估值处于历史低位", "趋势确立")));

        assertThat(validator.validate(JudgmentBlocks.context(block, ""))).isEmpty();
    }

    @Test
    @DisplayName("越界标签报 out_of_vocabulary 并给出下标")
    void rejectsUnknownTag() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("logic_tags", new ArrayList<>(List.of("估值处于历史低位", "我自创的标签")));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("out_of_vocabulary");
        assertThat(errors.get(0).path()).isEqualTo("$.logic_tags[1]");
        assertThat(errors.get(0).message()).contains("我自创的标签");
    }

    @Test
    @DisplayName("重复标签报 duplicate_tag")
    void rejectsDuplicateTag() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("logic_tags", new ArrayList<>(List.of("业绩改善", "业绩改善")));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).extracting(ValidationError::rule).contains("duplicate_tag");
    }

    @Test
    @DisplayName("非字符串标签报 type")
    void rejectsNonStringTag() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("logic_tags", new ArrayList<>(List.of(123)));

        List<ValidationError> errors = validator.validate(JudgmentBlocks.context(block, ""));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("type");
    }

    @Test
    @DisplayName("缺 logic_tags 或空数组只交给 Schema 报（此处不重复报错）")
    void missingTagsDelegatedToSchema() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.remove("logic_tags");
        Map<String, Object> empty = JudgmentBlocks.base();
        empty.put("logic_tags", List.of());

        assertThat(validator.validate(JudgmentBlocks.context(block, ""))).isEmpty();
        assertThat(validator.validate(JudgmentBlocks.context(empty, ""))).isEmpty();
    }
}
