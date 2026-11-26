package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("ClasspathTagVocabulary：词表加载与配置校验")
class ClasspathTagVocabularyTest {

    @Test
    @DisplayName("默认词表可加载且能按标签命中")
    void loadsDefaultVocabulary() {
        ClasspathTagVocabulary vocabulary = new ClasspathTagVocabulary();

        assertThat(vocabulary.size()).isGreaterThan(5);
        assertThat(vocabulary.contains("估值处于历史低位")).isTrue();
        assertThat(vocabulary.contains(" 估值处于历史低位 ")).isTrue();
        assertThat(vocabulary.contains("不存在的标签")).isFalse();
        assertThat(vocabulary.contains(null)).isFalse();
    }

    @Test
    @DisplayName("空词表 → 4005（空词表会静默放行所有标签）")
    void rejectsEmptyVocabulary() {
        ApiException error = catchThrowableOfType(
                () -> new ClasspathTagVocabulary("schema/logic_tags_empty.json"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("受控词表为空");
    }

    @Test
    @DisplayName("词表文件缺失 → 4005")
    void rejectsMissingVocabulary() {
        ApiException error = catchThrowableOfType(
                () -> new ClasspathTagVocabulary("schema/not_exists_tags.json"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("不存在");
    }
}
