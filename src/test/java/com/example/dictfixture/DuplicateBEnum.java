package com.example.dictfixture;

import com.arthur.refineloop.common.dict.DictEnum;

/** 测试夹具：见 {@link DuplicateAEnum}（同 type 同 code，用于触发重复编码保护）。 */
public enum DuplicateBEnum implements DictEnum {

    ONE("1", "壹");

    private final String code;
    private final String label;

    DuplicateBEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String dictType() {
        return "duplicateFixture";
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }
}
