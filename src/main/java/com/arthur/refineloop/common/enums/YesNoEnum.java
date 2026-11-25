package com.arthur.refineloop.common.enums;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 是/否（编码 {@code 1/0}）：布尔语义落库与传输的稳定映射。
 *
 * <p>为什么不直接用 {@code boolean}：SQLite 早期表用 INTEGER 0/1、外部接口用 Y/N 或 1/0，
 * 展示层还要「是/否」。统一走本枚举，落库/接口/展示三层就不会各写一套。
 */
public enum YesNoEnum implements DictEnum {

    YES("1", "是", "ok"),
    NO("0", "否", "off");

    private final String code;
    private final String label;
    private final String color;

    YesNoEnum(String code, String label, String color) {
        this.code = code;
        this.label = label;
        this.color = color;
    }

    @Override
    public String dictType() {
        return DictTypes.YES_NO;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String color() {
        return color;
    }

    @Override
    public int sort() {
        return this == YES ? 1 : 2;
    }

    public boolean isYes() {
        return this == YES;
    }

    /** {@code true → "1"}，{@code false → "0"}，{@code null → null}。 */
    public static String codeOf(Boolean value) {
        return value == null ? null : (value ? YES.code : NO.code);
    }

    /** {@code "1"/"Y"/"true"/"yes"} 视为是，其余为否（容忍外部接口口径）。 */
    public static YesNoEnum from(String code) {
        if (code == null) {
            return NO;
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "1", "Y", "YES", "TRUE" -> YES;
            default -> NO;
        };
    }
}
