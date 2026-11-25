package com.arthur.refineloop.common.validation;

import com.arthur.refineloop.common.dict.DictEnum;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link EnumCode} 的校验器：取值集合在初始化时从枚举常量里冻结，
 * 校验本身是 O(1) 的 {@code Set.contains}，不产生反射开销。
 *
 * <p>失败提示会列出允许取值（最多前 10 个），避免前端拿到「取值不合法」却不知道合法值是什么。
 */
public class EnumCodeValidator implements ConstraintValidator<EnumCode, String> {

    private Set<String> allowed = Set.of();
    private boolean allowBlank = true;
    private String message = "取值不在允许范围内";
    private String dictType = "";

    @Override
    public void initialize(EnumCode annotation) {
        Class<? extends DictEnum> enumType = annotation.value();
        this.allowBlank = annotation.allowBlank();
        this.message = annotation.message();
        DictEnum[] constants = enumType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            this.allowed = Set.of();
            return;
        }
        this.dictType = constants[0].dictType();
        this.allowed = java.util.Arrays.stream(constants)
                .map(DictEnum::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return allowBlank;
        }
        if (allowed.contains(value.trim())) {
            return true;
        }
        String sample = allowed.stream().limit(10).collect(Collectors.joining("/"));
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        message + "（" + (dictType.isEmpty() ? "" : "字典 " + dictType + " ") + "允许：" + sample + "）")
                .addConstraintViolation();
        return false;
    }
}
