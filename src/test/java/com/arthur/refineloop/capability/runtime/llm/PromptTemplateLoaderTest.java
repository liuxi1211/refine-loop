package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("PromptTemplateLoader：版本化加载与变量替换")
class PromptTemplateLoaderTest {

    private final PromptTemplateLoader loader = new PromptTemplateLoader();

    @Test
    @DisplayName("加载 classpath 下 prompts/{id}/{version}.md")
    void loadsVersionedPrompt() {
        String template = loader.load("daily_summary", "v1");
        assertThat(template).isNotBlank();
        assertThat(template).contains("tradeDate");
    }

    @Test
    @DisplayName("缺失版本抛 4105")
    void missingVersionFails() {
        ApiException error = catchThrowableOfType(
                () -> loader.load("daily_summary", "v99"), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4105);
    }

    @Test
    @DisplayName("非法 promptId / 路径穿越被拒绝")
    void invalidPromptIdRejected() {
        assertThat(catchThrowableOfType(
                () -> loader.load("../secret", "v1"), ApiException.class).getCode()).isEqualTo(4105);
        assertThat(catchThrowableOfType(
                () -> loader.load("Bad-Id", "v1"), ApiException.class).getCode()).isEqualTo(4105);
    }

    @Test
    @DisplayName("变量替换成功；缺变量抛 4107")
    void renderVariables() {
        String rendered = loader.render("hello {{name}}", Map.of("name", "world"));
        assertThat(rendered).isEqualTo("hello world");

        ApiException error = catchThrowableOfType(
                () -> loader.render("hello {{missing}}", Map.of()), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4107);
    }
}
