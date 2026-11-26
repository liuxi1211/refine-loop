package com.arthur.refineloop.capability.runtime.validation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 受控词表端口：{@code logic_tags} 只允许取这里面的标签。
 *
 * <p>为什么是端口而不是直接读表/读文件：M1 用 classpath 资源起步（随方法版本进 git），
 * 之后换成 {@code logic_tag_dict} 表实现时，校验链一行都不用改——
 * 标签来源会随方法版本演进，但「标签必须受控」这条约束不能变。
 *
 * <p>不变量：词表为空即配置错误（空词表会静默放行所有标签，比报错危险得多）。
 */
@FunctionalInterface
public interface TagVocabulary {

    /**
     * 标签是否在词表内。
     *
     * @param tag 标签（已 trim）
     * @return 是否受控
     */
    boolean contains(String tag);

    /** 由给定标签构造内存词表（测试与「小词表」场景用）。 */
    static TagVocabulary of(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("词表不能为空");
        }
        Set<String> copy = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        return copy::contains;
    }
}
