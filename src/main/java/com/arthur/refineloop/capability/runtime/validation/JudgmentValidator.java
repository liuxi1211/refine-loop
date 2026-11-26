package com.arthur.refineloop.capability.runtime.validation;

import java.util.List;

/**
 * L3 校验器契约：单一职责、无状态、可并发复用。
 *
 * <p>实现类不得持有跨调用的可变状态（校验器是单例 Bean，被 4 个并发标的复用）；
 * 需要缓存时只允许缓存「不可变的资源解析结果」（如已编译的 JSON Schema）。
 *
 * <p>失败语义：模型输出不合法 → 返回错误列表；配置缺陷（资源缺失/非法）→ 抛
 * {@code ApiException(4005)} 让流水线 fail-fast，避免用坏配置静默放过所有判断。
 */
public interface JudgmentValidator {

    /** 校验器标识（进 {@link ValidationError#validator()}，也用于回喂文本）。 */
    String name();

    /**
     * 校验一次。
     *
     * @param context 校验输入
     * @return 结构化错误；空列表表示通过
     */
    List<ValidationError> validate(ValidationContext context);
}
