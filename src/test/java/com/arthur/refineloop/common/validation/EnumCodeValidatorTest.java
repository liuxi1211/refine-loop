package com.arthur.refineloop.common.validation;

import com.arthur.refineloop.common.enums.YesNoEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** @EnumCode 校验：把「常量组 → 入参校验」闭环，失败提示必须列出允许取值。 */
class EnumCodeValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 合法code通过() {
        assertThat(validator.validate(new Payload("1", "0"))).isEmpty();
    }

    @Test
    void 非法code被拒绝并给出允许取值() {
        Set<ConstraintViolation<Payload>> violations = validator.validate(new Payload("9", "0"));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("yesNo")
                .contains("1/0");
    }

    @Test
    void 空值默认放行_allowBlank为false时拒绝() {
        assertThat(validator.validate(new Payload(null, "0"))).isEmpty();
        assertThat(validator.validate(new Payload(" ", "0"))).isEmpty();

        Set<ConstraintViolation<Payload>> violations = validator.validate(new Payload("1", null));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("required");
    }

    /** 测试用入参：可选 + 必填两档。 */
    public record Payload(
            @EnumCode(YesNoEnum.class) String yesNo,
            @EnumCode(value = YesNoEnum.class, allowBlank = false) String required) {
    }
}
