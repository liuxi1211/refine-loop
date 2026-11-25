package com.arthur.refineloop.common.util;

import com.arthur.refineloop.common.ApiException;

import java.util.Collection;

/**
 * 入参断言：失败即抛 {@link ApiException#badRequest}，最终统一变成 400 + 可读文案。
 *
 * <p>为什么不用 {@code Objects.requireNonNull}：它抛 NPE（500），而「前端没传必填参数」
 * 是调用方的错，必须是 400。把「参数错」和「程序错」在异常类型上分开，日志级别才能分开
 * （warn vs error）。
 *
 * <p>用法：{@code Asserts.notBlank(table, "table")} 一行完成判空 + 报错文案。
 */
public final class Asserts {

    private Asserts() {
    }

    public static <T> T notNull(T value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value;
    }

    public static String notBlank(String value, String field) {
        if (Strings.isBlank(value)) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw ApiException.badRequest(message);
        }
    }

    public static void isFalse(boolean expression, String message) {
        if (expression) {
            throw ApiException.badRequest(message);
        }
    }

    public static <T extends Collection<?>> T notEmpty(T value, String field) {
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value;
    }

    /** 字符串长度上限校验（防止超长入参打爆库/日志）。 */
    public static String maxLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw ApiException.badRequest(field + " 长度不能超过 " + maxLength + "（当前 " + value.length() + "）");
        }
        return value;
    }

    /** 闭区间校验。 */
    public static int inRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw ApiException.badRequest(field + " 必须为 " + min + "~" + max + "（当前 " + value + "）");
        }
        return value;
    }

    /** 业务规则拒绝（格式合法但不允许），code=1001。 */
    public static void reject(boolean condition, String message) {
        if (!condition) {
            throw ApiException.reject(message);
        }
    }
}
