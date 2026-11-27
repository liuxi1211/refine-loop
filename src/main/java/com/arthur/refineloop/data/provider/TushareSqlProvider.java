package com.arthur.refineloop.data.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 41 张 Tushare 镜像表的动态 SQL 提供器（MyBatis {@code @InsertProvider}/{@code @SelectProvider}）。
 *
 * <p>为什么用 Provider 而不是 41 份 XML/Mapper：这些表的列集合是「元数据驱动」的（注册表 {@code fields}
 * 串决定列），逐表手写 Mapper 维护成本高且易漂移（决策 D2）。Provider 在每次执行时按
 * {@code table/columns/keys} 生成 SQL，仍然走 MyBatis 的执行/批量/事务体系，而不是 JdbcTemplate 裸拼。
 *
 * <p>安全：所有拼进 SQL 的标识符（表名、列名、排序方向）都必须通过 {@link #identifier(String)}
 * 正则校验；值一律用 {@code #{...}} 预编译占位，不做字符串拼接。
 */
public final class TushareSqlProvider {

    /** SQL 标识符白名单（与注册表列名同规则）。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private TushareSqlProvider() {
    }

    // ================================================================ 写

    /** 普通批量插入（全量替换 / 整块删插的第二段）。 */
    public static String insert(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        List<String> columns = columns(params);
        return insertHead(table, columns);
    }

    /** {@code INSERT OR IGNORE}：表中只有幂等键列时退化为「插入或忽略」。 */
    public static String insertOrIgnore(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        List<String> columns = columns(params);
        return "INSERT OR IGNORE INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES (" + rowValues(columns) + ")";
    }

    /** {@code INSERT ... ON CONFLICT (幂等键) DO UPDATE SET 列 = excluded.列}（SQLite upsert）。 */
    public static String upsert(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        List<String> columns = columns(params);
        List<String> keys = identifiers(params, "keys");
        List<String> updateColumns = new ArrayList<>(columns.size());
        for (String column : columns) {
            if (!keys.contains(column)) {
                updateColumns.add(column);
            }
        }
        if (updateColumns.isEmpty()) {
            return insertOrIgnore(params);
        }
        StringBuilder sql = new StringBuilder(insertHead(table, columns))
                .append(" ON CONFLICT (").append(String.join(", ", keys)).append(") DO UPDATE SET ");
        List<String> assignments = new ArrayList<>(updateColumns.size());
        for (String column : updateColumns) {
            assignments.add(column + " = excluded." + column);
        }
        return sql.append(String.join(", ", assignments)).toString();
    }

    /** 全表删除（维表全量替换的第一段）。 */
    public static String deleteAll(Map<String, Object> params) {
        return "DELETE FROM " + identifier(str(params, "table"));
    }

    /** 按业务键删除整块（整块删插的第一段）。 */
    public static String deleteByKeys(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        List<String> keys = identifiers(params, "keys");
        List<String> conditions = new ArrayList<>(keys.size());
        for (String key : keys) {
            conditions.add(key + " = #{keyValues." + key + ",jdbcType=NULL}");
        }
        return "DELETE FROM " + table + " WHERE " + String.join(" AND ", conditions);
    }

    /** 按业务日期区间删除整块（月窗口回补上游返回空时，也必须清除旧块）。 */
    public static String deleteByRange(Map<String, Object> params) {
        return "DELETE FROM " + identifier(str(params, "table"))
                + " WHERE " + identifier(str(params, "column"))
                + " >= #{start} AND " + identifier(str(params, "column")) + " <= #{end}";
    }

    // ================================================================ 读

    public static String countAll(Map<String, Object> params) {
        return "SELECT COUNT(*) FROM " + identifier(str(params, "table"));
    }

    public static String maxValue(Map<String, Object> params) {
        return "SELECT MAX(" + identifier(str(params, "column")) + ") FROM "
                + identifier(str(params, "table"));
    }

    /** 单列取值（如股票清单 / 指数清单），{@code whereSql}/{@code orderBySql} 由调用方用常量构造。 */
    public static String selectColumn(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        String column = identifier(str(params, "column"));
        StringBuilder sql = new StringBuilder("SELECT ").append(column).append(" FROM ").append(table);
        String whereSql = optional(params, "whereSql");
        if (whereSql != null) {
            sql.append(" WHERE ").append(whereSql);
        }
        String orderBySql = optional(params, "orderBySql");
        if (orderBySql != null) {
            sql.append(" ORDER BY ").append(orderBySql);
        }
        Object limit = params.get("limit");
        if (limit instanceof Number number) {
            sql.append(" LIMIT ").append(number.longValue());
        }
        return sql.toString();
    }

    /** 按业务日期分组计数（近 N 日落库行数柱图）。 */
    public static String groupCount(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        String column = identifier(str(params, "column"));
        return "SELECT " + column + " AS d, COUNT(*) AS c FROM " + table
                + " WHERE " + column + " IS NOT NULL GROUP BY " + column
                + " ORDER BY d DESC LIMIT " + ((Number) params.get("limit")).longValue();
    }

    /** 数据预览计数：与 {@link #preview} 同一套过滤条件，供分页 total 使用。 */
    public static String countFiltered(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        String where = whereClause(params);
        return "SELECT COUNT(*) FROM " + table + where;
    }

    /** 数据预览：白名单列 + 可选代码/日期过滤 + LIMIT/OFFSET 保护。 */
    public static String preview(Map<String, Object> params) {
        String table = identifier(str(params, "table"));
        String columnsSql = columnsSql(params.get("columnsSql"));
        StringBuilder sql = new StringBuilder("SELECT ").append(columnsSql).append(" FROM ").append(table)
                .append(whereClause(params))
                .append(" ORDER BY ").append(str(params, "orderBySql"));
        sql.append(" LIMIT ").append(((Number) params.get("limit")).longValue());
        sql.append(" OFFSET ").append(((Number) params.get("offset")).longValue());
        return sql.toString();
    }

    /** 代码/日期区间过滤条件（标识符来自注册表，值用 {@code #{}} 预编译绑定）。 */
    private static String whereClause(Map<String, Object> params) {
        List<String> conditions = new ArrayList<>(3);
        if (optional(params, "code") != null) {
            conditions.add(identifier(str(params, "codeColumn")) + " = #{code}");
        }
        if (optional(params, "start") != null) {
            conditions.add(identifier(str(params, "dateColumn")) + " >= #{start}");
        }
        if (optional(params, "end") != null) {
            conditions.add(identifier(str(params, "dateColumn")) + " <= #{end}");
        }
        if (conditions.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    /** SQLite 列元数据（SQLite 无 information_schema）。 */
    public static String tableInfo(Map<String, Object> params) {
        return "PRAGMA table_info('" + identifier(str(params, "table")) + "')";
    }

    // ================================================================ helpers

    private static String insertHead(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + rowValues(columns) + ")";
    }

    /** 值的预编译占位：{@code #{row.<列>,jdbcType=NULL}}，缺列/空值统一落 NULL。 */
    private static String rowValues(List<String> columns) {
        List<String> values = new ArrayList<>(columns.size());
        for (String column : columns) {
            values.add("#{row." + column + ",jdbcType=NULL}");
        }
        return String.join(", ", values);
    }

    /** 预览列串校验：逗号分隔的每个标识符都必须合法（防注入）。 */
    private static String columnsSql(Object value) {
        String raw = value == null ? "" : String.valueOf(value);
        List<String> columns = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                columns.add(identifier(trimmed));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("预览列不能为空");
        }
        return String.join(", ", columns);
    }

    @SuppressWarnings("unchecked")
    private static List<String> columns(Map<String, Object> params) {
        Object value = params.get("columns");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("columns 不能为空");
        }
        List<String> columns = new ArrayList<>(list.size());
        for (Object item : list) {
            columns.add(identifier(String.valueOf(item)));
        }
        return columns;
    }

    private static List<String> identifiers(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(identifier(String.valueOf(item)));
        }
        return result;
    }

    private static String str(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) {
            throw new IllegalArgumentException("缺少 SQL 参数: " + name);
        }
        return String.valueOf(value);
    }

    private static String optional(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String identifier(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法 SQL 标识符（拒绝拼接）: " + raw);
        }
        return value;
    }
}
