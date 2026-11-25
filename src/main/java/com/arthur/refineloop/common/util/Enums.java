package com.arthur.refineloop.common.util;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.dict.DictEnum;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 枚举工具：面向 {@link DictEnum} 的按 code 反查，以及通用按名解析。
 *
 * <p>为什么需要它：数据库/接口里存的是 {@code code}（字符串），Java 侧需要强类型；
 * 手写 {@code Arrays.stream(...).filter(...)} 到处复制粘贴，迟早有一个地方忘了 trim 或大小写。
 *
 * <p>错误语义分两档：
 * <ul>
 *   <li>{@link #fromCode} → Optional（容错/展示场景）；</li>
 *   <li>{@link #requireCode} → 失败即 400，并列出允许取值（接口入参场景）。</li>
 * </ul>
 */
public final class Enums {

    private Enums() {
    }

    public static <E extends Enum<E> & DictEnum> Optional<E> fromCode(Class<E> type, String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.code().equals(normalized)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /** 精确匹配 code；未命中抛 400（含允许取值清单）。 */
    public static <E extends Enum<E> & DictEnum> E requireCode(Class<E> type, String code) {
        return fromCode(type, code).orElseThrow(() -> ApiException.badRequest(
                type.getSimpleName() + " 取值非法（当前 " + code + "），允许：" + String.join("/", codes(type))));
    }

    public static <E extends Enum<E> & DictEnum> String codeOf(E value) {
        return value == null ? null : value.code();
    }

    public static <E extends Enum<E> & DictEnum> String labelOf(E value) {
        return value == null ? null : value.label();
    }

    public static <E extends Enum<E> & DictEnum> List<String> codes(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(DictEnum::code).toList();
    }

    /** 按枚举名解析（不区分大小写，容忍前后空白）；未命中返回 empty。 */
    public static <E extends Enum<E>> Optional<E> fromName(Class<E> type, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(normalized)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    public static <E extends Enum<E>> E fromName(Class<E> type, String name, E defaultValue) {
        return fromName(type, name).orElse(defaultValue);
    }
}
