package com.arthur.refineloop.common.constant;

/**
 * 日期时间格式常量（字符串形态，供注解 {@code @JsonFormat(pattern=...)}、SQL 拼接、
 * 前端约定等处复用；{@code DateTimeFormatter} 实例见 {@code common.util.DateTimes}）。
 *
 * <p>本项目两条硬约定：
 * <ul>
 *   <li>业务日期（交易日、报告期）一律 {@link #DATE_COMPACT}（{@code yyyyMMdd}）字符串，与 Tushare 同口径；</li>
 *   <li>系统时间戳一律 {@link #DATE_TIME}（{@code yyyy-MM-dd HH:mm:ss}）文本，落 SQLite 时与
 *       {@code date_class=TEXT} 一致。</li>
 * </ul>
 */
public final class DatePatterns {

    /** 业务日期：20261124。 */
    public static final String DATE_COMPACT = "yyyyMMdd";

    /** 年月：202611。 */
    public static final String MONTH_COMPACT = "yyyyMM";

    /** 标准日期：2026-11-24。 */
    public static final String DATE = "yyyy-MM-dd";

    /** 标准日期时间（秒级）：2026-11-24 15:30:00。 */
    public static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    /** 带毫秒：2026-11-24 15:30:00.123（仅日志/排查用）。 */
    public static final String DATE_TIME_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS";

    /** 时间：15:30:00。 */
    public static final String TIME = "HH:mm:ss";

    /** 文件名安全的时间戳：20261124-153000。 */
    public static final String FILE_STAMP = "yyyyMMdd-HHmmss";

    private DatePatterns() {
    }
}
