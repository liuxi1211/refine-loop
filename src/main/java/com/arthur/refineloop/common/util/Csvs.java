package com.arthur.refineloop.common.util;

import java.util.List;

/**
 * CSV 生成工具：数据预览导出、报告附表用。
 *
 * <p>只做最必要的事：RFC 4180 转义（含逗号/引号/换行的字段加引号并把内部引号翻倍）、null → 空串、
 * CRLF 行尾（Excel 兼容性最好）。复杂需求（流式导出百万行）再引入专用库。
 */
public final class Csvs {

    private static final String CRLF = "\r\n";

    private Csvs() {
    }

    public static String toCsv(List<String> headers, List<? extends List<?>> rows) {
        StringBuilder sb = new StringBuilder();
        if (headers != null && !headers.isEmpty()) {
            appendRow(sb, headers);
        }
        if (rows != null) {
            for (List<?> row : rows) {
                appendRow(sb, row);
            }
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append(CRLF);
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        boolean needsQuote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!needsQuote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
