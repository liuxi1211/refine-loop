package com.arthur.refineloop.judgment.model;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 主判断方向（能力详案 3.2 {@code direction.type}）。
 *
 * <p>为什么实现 {@link DictEnum}：前端筛选与看板 badge 的文案、颜色不能写死在页面里（前端规范
 * 05 §6），而方向是本系统最高频的筛选维度；登记进字典后前端一次拉取即可渲染。
 *
 * <p>{@code code} 即落库值（{@code judgment.direction} 冗余列 + payload），发布后不可改。
 */
public enum JudgmentDirectionEnum implements DictEnum {

    BULLISH("bullish", "看多", "ok", 1),
    BEARISH("bearish", "看空", "err", 2),
    NEUTRAL("neutral", "中性", "idle", 3),
    CONDITIONAL("conditional", "有条件", "warn", 4);

    private final String code;
    private final String label;
    private final String color;
    private final int sort;

    JudgmentDirectionEnum(String code, String label, String color, int sort) {
        this.code = code;
        this.label = label;
        this.color = color;
        this.sort = sort;
    }

    @Override
    public String dictType() {
        return DictTypes.JUDGMENT_DIRECTION;
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
        return sort;
    }
}
