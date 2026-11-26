package com.arthur.refineloop.judgment.model;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 判断置信度（能力详案 3.1/3.2 {@code confidence}，三档枚举：高/中/低）。
 *
 * <p><b>为什么 code 直接用中文</b>：判断 Schema 是<b>对外契约</b>（payload 里存的就是「高/中/低」），
 * 框架的机械降级块（{@code InterpretResult.degraded}）也写「低」。若这里用 {@code HIGH}/{@code MEDIUM}
 * 再映射一次，就会出现「payload 是中文、列是英文、前端再翻译」的三重口径——判断资产的原文必须与契约一致。
 *
 * <p>落库列 {@code judgment.confidence} 是 {@code VARCHAR(4)}，中文在 SQLite 中按 UTF-8 存储，
 * 长度校验按字符数而非字节（DDL 的 VARCHAR 长度在 SQLite 不强制，见规范 02 §5）。
 */
public enum JudgmentConfidenceEnum implements DictEnum {

    HIGH("高", "高", "ok", 1),
    MEDIUM("中", "中", "warn", 2),
    LOW("低", "低", "err", 3);

    private final String code;
    private final String label;
    private final String color;
    private final int sort;

    JudgmentConfidenceEnum(String code, String label, String color, int sort) {
        this.code = code;
        this.label = label;
        this.color = color;
        this.sort = sort;
    }

    @Override
    public String dictType() {
        return DictTypes.CONFIDENCE_LEVEL;
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
