package com.arthur.refineloop.common.enums;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 启用状态（编码 {@code 1/0}）：配置项、数据表、任务开关的统一表达。
 *
 * <p>与 {@link YesNoEnum} 的区别是语义：后者回答「是不是」，前者回答「开没开」，
 * 展示文案也不同（启用/停用 vs 是/否）。两者不要混用。
 */
public enum EnableStatusEnum implements DictEnum {

    ENABLED("1", "启用", "ok"),
    DISABLED("0", "停用", "off");

    private final String code;
    private final String label;
    private final String color;

    EnableStatusEnum(String code, String label, String color) {
        this.code = code;
        this.label = label;
        this.color = color;
    }

    @Override
    public String dictType() {
        return DictTypes.ENABLE_STATUS;
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
        return this == ENABLED ? 1 : 2;
    }

    public boolean isEnabled() {
        return this == ENABLED;
    }

    public static String codeOf(boolean enabled) {
        return enabled ? ENABLED.code : DISABLED.code;
    }

    public static EnableStatusEnum from(String code) {
        return "0".equals(code) ? DISABLED : ENABLED;
    }
}
