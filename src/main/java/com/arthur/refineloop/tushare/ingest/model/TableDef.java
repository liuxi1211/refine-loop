package com.arthur.refineloop.tushare.ingest.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一张 Tushare 数据表的完整元数据（不可变）。
 *
 * <p>签名严格对齐《T2~T5 开发协作契约》§5.1：T4 编排、运维 API、质量门都只依赖本记录暴露的访问器，
 * 不做任何硬编码表/列名的分支。
 *
 * @param tableName          物理表名（= db/schema.sql 表名，白名单校验用）
 * @param apiName            Tushare api_name（如 {@code stk_weekly_monthly}，一对多时两张表共用）
 * @param chineseName        中文名（前端展示）
 * @param group              分组
 * @param fields             逗号分隔的 fields 列串（与 schema 列逐列一致，顺序一致）
 * @param strategy           更新策略
 * @param writeMode          写入模式
 * @param idempotencyKeys    幂等键列（BLOCK_DELETE_INSERT 表为删除键列；全量替代表为空列表）
 * @param pageSize           单页行数（limit）
 * @param maxPages           最大翻页数（保护上限）
 * @param rateLimitPerMinute 官方限流（次/分钟，只读展示；实际限流阈值在 ratelimit.yml 留余量配置）
 * @param latestDateColumn   最新业务日期列（可为 {@code null}，如 sw_industry）
 * @param codeColumn         代码列（per-stock/per-index 代码来源；如 hk_hold=code、margin=exchange_id）
 * @param unitNote           单位口径短文本（前端抽屉展示；无特殊单位统一为 {@code "-"}）
 * @param options            特殊标记（不可变 Map；无特殊标记传 {@link Map#of()})
 */
public record TableDef(
        String tableName,
        String apiName,
        String chineseName,
        TableGroup group,
        String fields,
        IngestStrategy strategy,
        WriteMode writeMode,
        List<String> idempotencyKeys,
        int pageSize,
        int maxPages,
        int rateLimitPerMinute,
        String latestDateColumn,
        String codeColumn,
        String unitNote,
        Map<String, Object> options) {

    /** 合法 SQL 标识符（防注入兜底：注册表 fields 与所有动态拼 SQL 的标识符都必须匹配）。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public TableDef {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName 不能为空");
        }
        if (!SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 不是合法标识符: " + tableName);
        }
        if (apiName == null || apiName.isBlank()) {
            throw new IllegalArgumentException("apiName 不能为空: " + tableName);
        }
        if (fields == null || fields.isBlank()) {
            throw new IllegalArgumentException("fields 不能为空: " + tableName);
        }
        if (group == null || strategy == null || writeMode == null) {
            throw new IllegalArgumentException("group/strategy/writeMode 不能为空: " + tableName);
        }
        if (pageSize <= 0 || maxPages <= 0) {
            throw new IllegalArgumentException("pageSize/maxPages 必须为正数: " + tableName);
        }
        if (rateLimitPerMinute <= 0) {
            throw new IllegalArgumentException("rateLimitPerMinute 必须为正数: " + tableName);
        }
        fields = fields.trim();
        List<String> keys = new ArrayList<>();
        if (idempotencyKeys != null) {
            for (String key : idempotencyKeys) {
                if (key == null || !SAFE_IDENTIFIER.matcher(key).matches()) {
                    throw new IllegalArgumentException("非法幂等键列: " + tableName + "." + key);
                }
                keys.add(key);
            }
        }
        idempotencyKeys = List.copyOf(keys);
        unitNote = (unitNote == null || unitNote.isBlank()) ? "-" : unitNote;
        options = (options == null || options.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        // 校验 fields 中每个列名合法（构造期即拦截任何动态拼接注入）
        for (String column : splitColumns(fields)) {
            if (!SAFE_IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("非法列名: " + tableName + "." + column);
            }
        }
    }

    /**
     * fields 逗号切分后的不可变列名列表（顺序 = schema 列顺序）。
     *
     * <p>不缓存：调用方（写入器/编排）应在一次操作内只取一次，避免重复解析。
     */
    public List<String> columns() {
        return List.copyOf(splitColumns(fields));
    }

    /**
     * 每日增量编排是否默认启用：带 {@code disabledReason} 特殊标记的表默认停用（当前仅 hk_hold）。
     *
     * <p>运行期开关（sys_config 的 {@code ingest.daily.<table>}）由调用方覆盖本默认值。
     */
    public boolean dailyEnabledByDefault() {
        return !options.containsKey("disabledReason");
    }

    private static List<String> splitColumns(String fields) {
        String[] parts = fields.split(",");
        List<String> columns = new ArrayList<>(parts.length);
        for (String part : parts) {
            String column = part.trim();
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }
}
