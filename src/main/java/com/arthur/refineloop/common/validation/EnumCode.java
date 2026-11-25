package com.arthur.refineloop.common.validation;

import com.arthur.refineloop.common.dict.DictEnum;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验「取值必须是某个 {@link DictEnum} 的合法 code」。
 *
 * <p>用途：把「常量组 → 前端下拉框 → 后端入参校验」闭环。以前是控制器里手写
 * {@code Set.of("PENDING", ...)} 再比对（容易和枚举脱漂），现在直接注解：
 *
 * <pre>{@code
 * public record RetryRequest(@EnumCode(IngestJobStatus.class) String status) {}
 *
 * @PostMapping("/jobs")
 * public Result<Void> retry(@Valid @RequestBody RetryRequest req) { ... }
 * }</pre>
 *
 * <p>注意：本注解只校验「是否属于字典」，<b>不校验启用状态</b>；需要拒绝停用项时，
 * 业务层再调用 {@code DictService.isEnabledCode(...)} 并抛出明确的业务提示。
 * 空值默认放行（可选参数常见），必填请叠加 {@code @NotBlank}。
 */
@Documented
@Constraint(validatedBy = EnumCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EnumCode {

    /** 目标字典枚举类型。 */
    Class<? extends DictEnum> value();

    /** 是否允许 null/空白（默认允许）。 */
    boolean allowBlank() default true;

    /** 校验失败提示（会拼上允许取值，见 {@link EnumCodeValidator}）。 */
    String message() default "取值不在允许范围内";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
