package com.arthur.refineloop.common.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 字符串工具：只补 JDK 缺失的高频语义（判空、驼峰↔下划线、截断、切分）。
 *
 * <p>取舍说明：项目已有 commons-lang3 / Hutool 的版本登记，但实际未引入依赖，
 * 这里用 JDK 实现避免为几个方法拉入整库；出现更复杂需求时再引入，不要重复手写。</p>
 */
public final class Strings {

    private static final String ELLIPSIS = "…";

    private Strings() {
    }

    public static boolean isEmpty(CharSequence value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(CharSequence value) {
        return !isEmpty(value);
    }

    public static boolean isBlank(CharSequence value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(CharSequence value) {
        return !isBlank(value);
    }

    /** 去空白；空白串归一为 null（「空」只有一种表示，避免 "" 与 null 两套判断）。 */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 空白时返回默认值。 */
    public static String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    public static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    public static boolean containsIgnoreCase(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    /** 首字母大写（其余不动）。 */
    public static String upperFirst(String value) {
        if (isEmpty(value)) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public static String lowerFirst(String value) {
        if (isEmpty(value)) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * 小驼峰 → 下划线：{@code tsCode → ts_code}（Tushare 字段口径的反向转换）。
     *
     * <p>只对「标准小驼峰」保证正确；全大写缩写（{@code TS_CODE}）不适用，请原样书写。
     */
    public static String camelToUnderline(String value) {
        if (isEmpty(value)) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && value.charAt(i - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 下划线 → 小驼峰：{@code ts_code → tsCode}。 */
    public static String underlineToCamel(String value) {
        if (isEmpty(value) || value.indexOf('_') < 0) {
            return lowerFirst(value);
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            sb.append(i == 0 ? parts[i] : upperFirst(parts[i]));
        }
        return sb.toString();
    }

    /** 截断到 {@code maxLength} 个字符（含省略号）；用于日志与错误提示，避免刷屏。 */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength == 1) {
            return ELLIPSIS;
        }
        return value.substring(0, maxLength - 1) + ELLIPSIS;
    }

    /** 按分隔符切分，自动去空白并丢弃空段。 */
    public static List<String> splitToList(String value, String delimiter) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(delimiter))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /** 逗号分隔串 → 列表（最高频的切分场景）。 */
    public static List<String> splitComma(String value) {
        return splitToList(value, ",");
    }

    /** 集合 → 逗号分隔串（跳过 null/空白）。 */
    public static String joinComma(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    public static String repeat(String value, int times) {
        return value == null || times <= 0 ? "" : value.repeat(times);
    }

    /** 左填充（对齐日志/报表里的数字）。 */
    public static String leftPad(String value, int length, char padChar) {
        String source = nullToEmpty(value);
        if (source.length() >= length) {
            return source;
        }
        return String.valueOf(padChar).repeat(length - source.length()) + source;
    }
}
