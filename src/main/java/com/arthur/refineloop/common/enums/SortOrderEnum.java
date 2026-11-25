package com.arthur.refineloop.common.enums;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 排序方向（编码 {@code asc/desc}，小写，与前端查询参数一致）。
 *
 * <p>只表达「方向」，不表达「按哪个字段排」——字段名必须走白名单校验，禁止拼接用户输入。
 */
public enum SortOrderEnum implements DictEnum {

    ASC("asc", "升序", null),
    DESC("desc", "降序", null);

    private final String code;
    private final String label;
    private final String color;

    SortOrderEnum(String code, String label, String color) {
        this.code = code;
        this.label = label;
        this.color = color;
    }

    @Override
    public String dictType() {
        return DictTypes.SORT_ORDER;
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
        return this == ASC ? 1 : 2;
    }

    /** 解析：空白/未知一律按默认 DESC（列表场景最常见的「最新在前」）。 */
    public static SortOrderEnum from(String value) {
        return DESC.code.equalsIgnoreCase(value == null ? null : value.trim()) ? DESC : ASC;
    }
}
