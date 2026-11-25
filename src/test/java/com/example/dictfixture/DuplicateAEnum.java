package com.example.dictfixture;

import com.arthur.refineloop.common.dict.DictEnum;

/**
 * 测试夹具：与 {@link DuplicateBEnum} 声明同一个 dictType + code，用于验证
 * 「枚举重复编码 → 启动即失败」的守护逻辑。
 *
 * <p>刻意放在 {@code com.example} 下：主应用的字典扫描范围是 {@code com.arthur.refineloop}，
 * 不会被这两个故意冲突的夹具污染。
 */
public enum DuplicateAEnum implements DictEnum {

    ONE("1", "一");

    private final String code;
    private final String label;

    DuplicateAEnum(String code, String label) {
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
