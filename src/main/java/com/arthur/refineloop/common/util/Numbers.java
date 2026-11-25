package com.arthur.refineloop.common.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * 数字解析工具：外部接口（Tushare/CSV）返回的「数字」几乎都是字符串，
 * 且空串、{@code --}、{@code NaN}、{@code 1.0} 混着来，统一在这里做宽容解析。
 *
 * <p>宽容但<b>不隐瞒</b>：解析失败返回 {@code Optional.empty}/{@code null} 或调用方指定的默认值，
 * 不静默当成 0——0 在金融数据里是有意义的真实值。
 */
public final class Numbers {

    private Numbers() {
    }

    /** 解析整数；失败返回 empty。容忍 {@code "1"}、{@code "1.0"}、{@code " 1 "}。 */
    public static OptionalInt tryParseInt(String value) {
        BigDecimal decimal = Decimals.ofNullable(value);
        if (decimal == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(decimal.intValueExact());
        } catch (ArithmeticException e) {
            return OptionalInt.empty();
        }
    }

    public static int parseInt(String value, int defaultValue) {
        return tryParseInt(value).orElse(defaultValue);
    }

    public static Integer parseIntOrNull(String value) {
        OptionalInt parsed = tryParseInt(value);
        return parsed.isPresent() ? parsed.getAsInt() : null;
    }

    public static OptionalLong tryParseLong(String value) {
        BigDecimal decimal = Decimals.ofNullable(value);
        if (decimal == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(decimal.longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    public static long parseLong(String value, long defaultValue) {
        return tryParseLong(value).orElse(defaultValue);
    }

    /** 解析小数；空白/非法返回 empty。 */
    public static Optional<BigDecimal> tryParseDecimal(String value) {
        return Optional.ofNullable(Decimals.ofNullable(value));
    }

    /** 解析布尔（{@code 1/0/Y/N/true/false/是/否}）；无法识别返回 empty。 */
    public static Optional<Boolean> tryParseBoolean(String value) {
        if (Strings.isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "y", "yes", "true", "t", "是" -> Optional.of(true);
            case "0", "n", "no", "false", "f", "否" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean isInteger(String value) {
        return tryParseInt(value).isPresent();
    }

    /** 是否为「缺失」表现（空串、{@code --}、{@code NaN}、{@code None}、{@code null}）。 */
    public static boolean isMissing(String value) {
        if (Strings.isBlank(value)) {
            return true;
        }
        String normalized = value.trim();
        return "--".equals(normalized)
                || "nan".equalsIgnoreCase(normalized)
                || "none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized);
    }
}
