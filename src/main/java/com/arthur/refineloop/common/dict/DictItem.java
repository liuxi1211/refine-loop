package com.arthur.refineloop.common.dict;

/**
 * 下发前端的单个字典项（{@code {code, label, sort, color, enabled}}）。
 *
 * <p>字段刻意保持极简：前端 {@code DictSelect} 用 code/label，{@code DictTag} 用 code/label/color，
 * 下拉排序用 sort。需要附加业务属性（如图标、单位）时，请另开接口，不要把任意 Map 塞进来——
 * 字典是「稳定映射」，不是数据通道。
 */
public record DictItem(String code, String label, int sort, String color, boolean enabled) {

    public static DictItem of(DictEnum dictEnum) {
        return new DictItem(
                dictEnum.code(),
                dictEnum.label(),
                dictEnum.sort(),
                dictEnum.color(),
                dictEnum.enabled());
    }

    public static DictItem of(String code, String label) {
        return new DictItem(code, label, 0, null, true);
    }

    public static DictItem of(String code, String label, int sort, String color) {
        return new DictItem(code, label, sort, color, true);
    }
}
