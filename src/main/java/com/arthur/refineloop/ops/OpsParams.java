package com.arthur.refineloop.ops;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 运维读模型共用的参数校验（控制器与服务共用的静态工具，避免逻辑漂移）。
 *
 * <p>约定（契约 §9）：表名白名单来自 {@link TushareTableRegistry}，表不存在 → 404；
 * 列模式/日期格式等参数非法 → 400。分页参数校验统一由 {@code common.page.PageQuery} 负责，
 * 本类只补运维特有的深度限制（{@link #requirePreviewDepth}）。
 */
public final class OpsParams {

    /** 预览分页深度上限（契约 §9.1：page*size 超 10000 返回 400）。 */
    public static final long MAX_PREVIEW_DEPTH = 10_000L;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{8}");
    /** 代码列过滤值长度上限（ts_code 最长 12，留余量）。 */
    private static final int MAX_CODE_LENGTH = 32;

    private OpsParams() {
    }

    /** 表名白名单校验；未注册 → {@code ApiException.notFound}。 */
    public static TableDef requireTable(String table) {
        TableDef def = TushareTableRegistry.byTable(table);
        if (def == null) {
            throw ApiException.notFound("未知数据表: " + table);
        }
        return def;
    }

    /** 预览分页深度校验：page*size ≤ 10000，否则 400「预览分页超限」。 */
    public static void requirePreviewDepth(PageQuery query) {
        if ((long) query.page() * query.size() > MAX_PREVIEW_DEPTH) {
            throw ApiException.badRequest("预览分页超限：page*size 不能超过 " + MAX_PREVIEW_DEPTH);
        }
    }

    /** 可选日期参数规范化：空白 → null；非 {@code yyyyMMdd} → 400。 */
    public static String optionalDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!DATE_PATTERN.matcher(trimmed).matches() || !isParsable(trimmed)) {
            throw ApiException.badRequest(field + " 必须为 yyyyMMdd 格式（当前 " + value + "）");
        }
        return trimmed;
    }

    /** 区间参数校验：start ≤ end（均可空）。 */
    public static void requireRangeOrder(String start, String end) {
        if (start != null && end != null && start.compareTo(end) > 0) {
            throw ApiException.badRequest("start 不能晚于 end（" + start + " > " + end + "）");
        }
    }

    /** 可选代码参数规范化：去空白；超长 → 400。 */
    public static String optionalCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.length() > MAX_CODE_LENGTH) {
            throw ApiException.badRequest("code 长度不能超过 " + MAX_CODE_LENGTH + "（当前 " + trimmed.length() + "）");
        }
        return trimmed;
    }

    /** 枚举型查询参数规范化：空白取默认值；不在候选中 → 400。 */
    public static String enumParam(String value, String defaultValue, String field, String... allowed) {
        String normalized = (value == null || value.isBlank()) ? defaultValue : value.trim().toLowerCase();
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw ApiException.badRequest(field + " 仅支持 " + String.join("/", allowed) + "（当前 " + value + "）");
    }

    private static boolean isParsable(String date) {
        try {
            LocalDate.parse(date, DATE_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
