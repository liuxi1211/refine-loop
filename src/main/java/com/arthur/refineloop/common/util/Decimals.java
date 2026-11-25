package com.arthur.refineloop.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * 金额/指标计算工具：量化数据里最容易出问题的是「null、除零、精度」三件事，全部收敛到这里。
 *
 * <p>约定：
 * <ul>
 *   <li>指标统一保留 {@link #DEFAULT_SCALE} 位、{@link RoundingMode#HALF_UP}；</li>
 *   <li>入参 null 视为「缺失」，参与加法时按 0 处理，参与除法时结果为 null（缺失不能变成 0，
 *       否则会把「没数据」算成「0 增长」）；</li>
 *   <li>除法一律显式给 scale，禁止用 {@code BigDecimal.divide} 无参版本（除不尽会抛异常）。</li>
 * </ul>
 */
public final class Decimals {

    public static final int DEFAULT_SCALE = 4;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final MathContext MC = new MathContext(16, DEFAULT_ROUNDING);

    private Decimals() {
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static BigDecimal of(long value) {
        return BigDecimal.valueOf(value);
    }

    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value);
    }

    /** 字符串转 BigDecimal；空白/非法返回 null（外部接口字段常为空串）。 */
    public static BigDecimal ofNullable(String value) {
        if (Strings.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if ("--".equals(trimmed) || "nan".equalsIgnoreCase(trimmed) || "none".equalsIgnoreCase(trimmed)
                || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal add(BigDecimal left, BigDecimal right) {
        return nullToZero(left).add(nullToZero(right));
    }

    public static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return nullToZero(left).subtract(nullToZero(right));
    }

    public static BigDecimal multiply(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.multiply(right);
    }

    /** 除法：分母 null/0 → null（缺失语义，不抛异常、不返回 0）。 */
    public static BigDecimal divideOrNull(BigDecimal dividend, BigDecimal divisor) {
        return divideOrNull(dividend, divisor, DEFAULT_SCALE);
    }

    public static BigDecimal divideOrNull(BigDecimal dividend, BigDecimal divisor, int scale) {
        if (dividend == null || divisor == null || divisor.signum() == 0) {
            return null;
        }
        return dividend.divide(divisor, scale, DEFAULT_ROUNDING);
    }

    /** 百分比（分母为 total，结果 ×100）；分母缺失/为 0 → null。 */
    public static BigDecimal percent(BigDecimal part, BigDecimal total, int scale) {
        BigDecimal ratio = divideOrNull(part, total, scale + 2);
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100)).setScale(scale, DEFAULT_ROUNDING);
    }

    /** 保留小数位。 */
    public static BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, DEFAULT_ROUNDING);
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return ZERO;
        }
        BigDecimal total = ZERO;
        for (BigDecimal value : values) {
            total = total.add(nullToZero(value));
        }
        return total;
    }

    /** 平均值：忽略 null；全为 null 时返回 null。 */
    public static BigDecimal average(Collection<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal total = ZERO;
        int count = 0;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
                count++;
            }
        }
        return count == 0 ? null : divideOrNull(total, BigDecimal.valueOf(count), DEFAULT_SCALE);
    }

    public static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    public static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    public static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    /* ---------------- null 安全比较（null 视为最小） ---------------- */

    public static boolean gt(BigDecimal left, BigDecimal right) {
        return compare(left, right) > 0;
    }

    public static boolean ge(BigDecimal left, BigDecimal right) {
        return compare(left, right) >= 0;
    }

    public static boolean lt(BigDecimal left, BigDecimal right) {
        return compare(left, right) < 0;
    }

    public static boolean le(BigDecimal left, BigDecimal right) {
        return compare(left, right) <= 0;
    }

    public static boolean eq(BigDecimal left, BigDecimal right) {
        return compare(left, right) == 0;
    }

    private static int compare(BigDecimal left, BigDecimal right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    /** 展示用：null → 占位；否则去科学计数法。 */
    public static String toPlainString(BigDecimal value, String nullText) {
        return value == null ? nullText : value.stripTrailingZeros().toPlainString();
    }

    /** 转 double（仅用于需要 double 的库，如 ta4j/commons-math）；null → Double.NaN。 */
    public static double toDouble(BigDecimal value) {
        return value == null ? Double.NaN : value.doubleValue();
    }
}
