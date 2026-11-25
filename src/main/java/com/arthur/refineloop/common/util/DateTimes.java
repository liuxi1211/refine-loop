package com.arthur.refineloop.common.util;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.constant.DatePatterns;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 日期时间工具：格式化/解析/边界/交易日格式的集中入口。
 *
 * <p>两条口径（与架构文档一致，改动会牵连库与前端）：
 * <ul>
 *   <li><b>业务日期</b>（交易日、报告期）用 {@code yyyyMMdd} 字符串，与 Tushare 一致；</li>
 *   <li><b>系统时间</b>用 {@code yyyy-MM-dd HH:mm:ss}，与 SQLite {@code date_class=TEXT} 一致。</li>
 * </ul>
 *
 * <p>取「现在」请注入 {@link Clock} 后调用 {@link #today(Clock)}/{@link #now(Clock)}，
 * 不要在业务代码里直接 {@code LocalDate.now()}——否则单测只能靠改系统时间。
 */
public final class DateTimes {

    public static final DateTimeFormatter DATE_COMPACT = DateTimeFormatter.ofPattern(DatePatterns.DATE_COMPACT);
    public static final DateTimeFormatter MONTH_COMPACT = DateTimeFormatter.ofPattern(DatePatterns.MONTH_COMPACT);
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(DatePatterns.DATE);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(DatePatterns.DATE_TIME);
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(DatePatterns.TIME);
    public static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern(DatePatterns.FILE_STAMP);

    private DateTimes() {
    }

    /* ---------------- 业务日期 yyyyMMdd ---------------- */

    /** 解析 {@code yyyyMMdd}；格式非法抛 400（用户输入场景）。 */
    public static LocalDate parseCompactDate(String value) {
        if (Strings.isBlank(value)) {
            throw ApiException.badRequest("日期不能为空，格式 yyyyMMdd");
        }
        try {
            return LocalDate.parse(value.trim(), DATE_COMPACT);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("日期格式必须为 yyyyMMdd（当前 " + value + "）");
        }
    }

    public static Optional<LocalDate> tryParseCompactDate(String value) {
        if (Strings.isBlank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.trim(), DATE_COMPACT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 格式化 {@code yyyyMMdd}；null → null（便于直接落库）。 */
    public static String formatCompact(LocalDate date) {
        return date == null ? null : date.format(DATE_COMPACT);
    }

    /** 格式化 {@code yyyyMMdd}；null → 指定占位（前端展示场景）。 */
    public static String formatCompact(LocalDate date, String nullText) {
        return date == null ? nullText : date.format(DATE_COMPACT);
    }

    /* ---------------- 系统时间 ---------------- */

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    public static String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME);
    }

    public static String formatDate(LocalDate value) {
        return value == null ? null : value.format(DATE);
    }

    /** 文件名安全时间戳：{@code 20261124-153000}。 */
    public static String formatFileStamp(LocalDateTime value) {
        return value == null ? null : value.format(FILE_STAMP);
    }

    /** 解析 {@code yyyy-MM-dd HH:mm:ss}（也容忍 {@code T} 分隔与省略秒）。 */
    public static Optional<LocalDateTime> tryParseDateTime(String value) {
        if (Strings.isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('T', ' ');
        try {
            return Optional.of(LocalDateTime.parse(normalized, DATE_TIME));
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他常见形态
        }
        try {
            return Optional.of(LocalDate.parse(normalized, DATE).atStartOfDay());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /* ---------------- 边界与区间 ---------------- */

    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(23, 59, 59);
    }

    /** {@code from} 到 {@code to} 的天数（to 更晚为正）。 */
    public static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }

    public static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6;
    }

    /** 判断 {@code date} 是否落在 {@code [start, end]}（任一端为 null 视为该端不限制）。 */
    public static boolean between(LocalDate date, LocalDate start, LocalDate end) {
        if (date == null) {
            return false;
        }
        if (start != null && date.isBefore(start)) {
            return false;
        }
        return end == null || !date.isAfter(end);
    }

    /** 人类可读时长：{@code 850ms / 8.2s / 22m48s / 1h05m}（与前端 formatDuration 同口径）。 */
    public static String humanize(long millis) {
        if (millis < 0) {
            return "-";
        }
        if (millis < 1000) {
            return millis + "ms";
        }
        double seconds = millis / 1000.0;
        if (seconds < 60) {
            return String.format("%.1fs", seconds);
        }
        long totalMinutes = (long) (seconds / 60);
        long restSeconds = Math.round(seconds % 60);
        if (totalMinutes < 60) {
            return String.format("%dm%02ds", totalMinutes, restSeconds);
        }
        long hours = totalMinutes / 60;
        return String.format("%dh%02dm", hours, totalMinutes % 60);
    }

    public static String humanize(Duration duration) {
        return duration == null ? "-" : humanize(duration.toMillis());
    }

    /** 解析 ISO-8601 时长或纯秒数（用于配置项）。 */
    public static Optional<Duration> parseDuration(String value) {
        if (Strings.isBlank(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("P") || trimmed.startsWith("p")) {
                return Optional.of(Duration.parse(trimmed.toUpperCase(java.util.Locale.ROOT)));
            }
            return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
