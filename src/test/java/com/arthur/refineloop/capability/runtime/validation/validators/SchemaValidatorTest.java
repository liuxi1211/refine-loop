package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.ReferenceFacts;
import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("SchemaValidator：JSON Schema + 身份一致性")
class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    @DisplayName("合法判断块通过")
    void acceptsValidBlock() {
        assertThat(validator.validate(context(JudgmentBlocks.base(), "",
                JudgmentBlocks.SCHEMA_ID))).isEmpty();
    }

    @Test
    @DisplayName("缺必填字段（confidence）报 required")
    void rejectsMissingRequiredField() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.remove("confidence");

        List<ValidationError> errors = validator.validate(context(block, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).isNotEmpty();
        assertThat(errors).extracting(ValidationError::path).contains("$.confidence");
        assertThat(errors).extracting(ValidationError::rule).contains("required");
    }

    @Test
    @DisplayName("枚举越界（confidence=极高）报 enum")
    void rejectsOutOfEnumValue() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("confidence", "极高");

        List<ValidationError> errors = validator.validate(context(block, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).extracting(ValidationError::rule).contains("enum");
        assertThat(errors).extracting(ValidationError::path).contains("$.confidence");
    }

    @Test
    @DisplayName("facts_used 元素缺 unit 报 required，路径精确到下标")
    void rejectsFactWithoutUnit() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("facts_used", List.of(Map.of("metric", "x", "value", 1)));

        List<ValidationError> errors = validator.validate(context(block, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).extracting(ValidationError::path).contains("$.facts_used[0].unit");
    }

    @Test
    @DisplayName("判断块混入别的标的 → identity_mismatch")
    void rejectsForeignStockCode() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("stock_code", "600000.SH");

        List<ValidationError> errors = validator.validate(context(block, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).anySatisfy(error -> {
            assertThat(error.path()).isEqualTo("$.stock_code");
            assertThat(error.rule()).isEqualTo("identity_mismatch");
        });
    }

    @Test
    @DisplayName("判断块自报旧版本号 → identity_mismatch（版本冻结）")
    void rejectsVersionDrift() {
        Map<String, Object> block = JudgmentBlocks.base();
        block.put("unit_version", "v0.9");

        List<ValidationError> errors = validator.validate(context(block, "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).anySatisfy(error -> {
            assertThat(error.path()).isEqualTo("$.unit_version");
            assertThat(error.rule()).isEqualTo("identity_mismatch");
        });
    }

    @Test
    @DisplayName("空判断块：一条明确错误（不刷 required）")
    void rejectsEmptyBlock() {
        List<ValidationError> errors = validator.validate(context(Map.of(), "", JudgmentBlocks.SCHEMA_ID));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("required");
    }

    @Test
    @DisplayName("Schema 资源缺失 → 4005（配置缺陷 fail-fast）")
    void missingSchemaRaisesConfigError() {
        ApiException error = catchThrowableOfType(
                () -> validator.validate(context(JudgmentBlocks.base(), "", "not_exist-v1.json")),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4005);
        assertThat(error.getMessage()).contains("判断 Schema 不存在");
    }

    private ValidationContext context(Map<String, Object> block, String report, String schemaId) {
        FactBundle facts = ReferenceFacts.bundle();
        return new ValidationContext(facts, ReferenceFacts.payload(), block, report, null,
                JudgmentBlocks.UNIT_ID, JudgmentBlocks.UNIT_VERSION, schemaId);
    }
}
