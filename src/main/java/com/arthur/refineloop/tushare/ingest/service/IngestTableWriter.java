package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.data.batch.MyBatisBatchExecutor;
import com.arthur.refineloop.data.mapper.TushareTableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tushare 数据表的通用落库器（元数据驱动，替代 41 份手写 upsert XML/Mapper）。
 *
 * <p>ORM 改造后：所有 SQL 由 {@link TushareTableMapper}（MyBatis 动态 SQL Provider）生成并执行，
 * 批量写走 {@link MyBatisBatchExecutor}（MyBatis BATCH 执行器，500 行/批 + 短事务），
 * 本类只保留「元数据校验 + 值预处理 + SQLite 单写者串行化」三件事。
 *
 * <p>约束与纪律（对齐《T2~T5 开发协作契约》§5.3 与《手册》第 3.3/3.4 章）：
 * <ul>
 *   <li>标识符白名单：表名必须来自 {@link TushareTableRegistry}，列名必须在 {@code def.columns()} 内，
 *       否则抛 {@link IllegalArgumentException}（防 SQL 注入；Provider 侧还有标识符正则兜底）；</li>
 *   <li>列集合固定为 {@code def.columns()}，行里缺失的列按 {@code NULL} 落库，行里多余键忽略；</li>
 *   <li>值预处理：{@code Float/Double → BigDecimal.valueOf}，空白字符串按 {@code NULL}；</li>
 *   <li>UPSERT 语义：SQLite {@code INSERT ... ON CONFLICT (幂等键) DO UPDATE SET 列 = excluded.列}；</li>
 *   <li>SQLite 只有单写者：写操作通过 {@link #writeLock} 串行化，配合数据源 {@code busy_timeout} 避免
 *       并发写触发 {@code SQLITE_BUSY}（HTTP 拉数并发不受影响，只是落库串行）；</li>
 *   <li>HTTP 调用不在此层（调用方负责在事务外拉数据）。</li>
 * </ul>
 */
@Repository
public class IngestTableWriter {

    private static final Logger log = LoggerFactory.getLogger(IngestTableWriter.class);

    /**
     * SQLite 单写者锁：把本进程内的写操作串行化。
     *
     * <p>SQLite（WAL 模式）允许多读单写，跨连接写冲突靠 {@code busy_timeout} 等待；
     * per-stock 接入以 ≤30 并发拉数并落库时，若同时写会产生大量写锁排队。这里在应用层串行化，
     * 使写入行为确定、不依赖 busy_timeout 的极端情况。
     */
    private static final ReentrantLock writeLock = new ReentrantLock(true);

    private final MyBatisBatchExecutor batchExecutor;

    /**
     * @param batchExecutor MyBatis BATCH 执行器（所有写入都经由它，保证批量与事务语义统一）；
     *                      单条/批量 SQL 的 Mapper 类型在调用处显式给出（{@code TushareTableMapper}）
     */
    public IngestTableWriter(MyBatisBatchExecutor batchExecutor) {
        this.batchExecutor = Objects.requireNonNull(batchExecutor, "batchExecutor 不能为空");
    }

    /**
     * 批量 upsert：{@code INSERT ... ON CONFLICT (幂等键) DO UPDATE SET 非键列 = excluded.列}。
     *
     * <p>幂等键列（{@code def.idempotencyKeys()}）缺失/为空的行丢弃并记 warn 日志；返回实际写入行数。
     * 若幂等键为空（全量替代表），退化为普通批量插入；若表中只有键列，退化为 {@code INSERT OR IGNORE}。
     */
    public int writeUpsert(TableDef def, List<Map<String, Object>> rows) {
        List<String> columns = requireRegistered(def);
        List<String> keys = requireKeysInColumns(def, columns);
        List<Map<String, Object>> valid = validateRows(def, rows, keys);
        if (valid.isEmpty()) {
            return 0;
        }
        String table = def.tableName();
        return withWriteLock(() -> {
            List<Map<String, Object>> normalized = normalizeAll(valid);
            if (keys.isEmpty()) {
                return batchExecutor.execute(TushareTableMapper.class, normalized,
                        (m, row) -> m.insertRow(table, columns, row));
            }
            return batchExecutor.execute(TushareTableMapper.class, normalized,
                    (m, row) -> m.upsert(table, columns, keys, row));
        });
    }

    /**
     * 全量替换：同事务 {@code DELETE FROM table}（不带条件）后批量插入，用于维表。
     *
     * <p>空结果集不执行替换（避免误清表），返回 0 并记 warn。
     */
    public int replaceAll(TableDef def, List<Map<String, Object>> rows) {
        List<String> columns = requireRegistered(def);
        if (rows == null || rows.isEmpty()) {
            log.warn("replaceAll 空结果，跳过整表替换以保护数据：table={}", def.tableName());
            return 0;
        }
        String table = def.tableName();
        List<Map<String, Object>> normalized = normalizeAll(rows);
        return withWriteLock(() -> {
            batchExecutor.executeInTransaction(TushareTableMapper.class, m -> {
                m.deleteAll(table);
                for (Map<String, Object> row : normalized) {
                    m.insertRow(table, columns, row);
                }
            });
            return normalized.size();
        });
    }

    /**
     * 整块先删后插：按 {@code blockKeyColumns} 的值（取 {@code blockRows} 第一行）删除同键旧块，再批量插入新块。
     *
     * <p>同一 block 内的行必须共享同一组键值，否则抛 {@link IllegalArgumentException}（避免误删其他块）。
     * 用于 top_list / pledge_detail / repurchase / top10_holders / top10_floatholders / report_rc。
     */
    public int deleteAndInsertBlock(TableDef def, List<String> blockKeyColumns,
                                    List<Map<String, Object>> blockRows) {
        List<String> columns = requireRegistered(def);
        List<String> deleteKeys = requireBlockKeys(def, columns, blockKeyColumns);
        if (blockRows == null || blockRows.isEmpty()) {
            log.debug("deleteAndInsertBlock 空块，跳过：table={} keys={}", def.tableName(), deleteKeys);
            return 0;
        }
        Map<String, Object> first = blockRows.get(0);
        Map<String, Object> keyValues = new java.util.LinkedHashMap<>();
        for (String key : deleteKeys) {
            Object value = first.get(key);
            if (isBlank(value)) {
                throw new IllegalArgumentException("整块删插的删除键 " + def.tableName() + "." + key
                        + " 为空白，无法定位删除范围");
            }
            keyValues.put(key, normalize(value));
        }
        for (int i = 1; i < blockRows.size(); i++) {
            Map<String, Object> row = blockRows.get(i);
            for (String key : deleteKeys) {
                if (!Objects.equals(normalize(first.get(key)), normalize(row.get(key)))) {
                    throw new IllegalArgumentException("整块删插要求同一 block 内键值一致：table=" + def.tableName()
                            + "，列=" + key);
                }
            }
        }
        String table = def.tableName();
        List<Map<String, Object>> normalized = normalizeAll(blockRows);
        return withWriteLock(() -> {
            batchExecutor.executeInTransaction(TushareTableMapper.class, m -> {
                m.deleteByKeys(table, deleteKeys, keyValues);
                for (Map<String, Object> row : normalized) {
                    m.insertRow(table, columns, row);
                }
            });
            return normalized.size();
        });
    }

    /** 上游返回空块时按相等键清除旧块；键必须属于本表且非空，防止误删整表。 */
    public void deleteBlock(TableDef def, Map<String, Object> keyValues) {
        List<String> columns = requireRegistered(def);
        if (keyValues == null || keyValues.isEmpty()) {
            throw new IllegalArgumentException("deleteBlock 的 keyValues 不能为空: " + def.tableName());
        }
        List<String> keys = new ArrayList<>(keyValues.size());
        keyValues.forEach((key, value) -> {
            if (key == null || !columns.contains(key) || isBlank(value)) {
                throw new IllegalArgumentException("deleteBlock 键非法或值为空: " + def.tableName() + "." + key);
            }
            keys.add(key);
        });
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        keyValues.forEach((key, value) -> normalized.put(key, normalize(value)));
        String table = def.tableName();
        withWriteLock(() -> {
            batchExecutor.executeInTransaction(TushareTableMapper.class,
                    mapper -> mapper.deleteByKeys(table, keys, normalized));
            return null;
        });
    }

    /** 按业务日期区间清除整块；用于月窗口回补返回空结果的场景。 */
    public void deleteByDateRange(TableDef def, String column, String start, String end) {
        List<String> columns = requireRegistered(def);
        if (column == null || !columns.contains(column)) {
            throw new IllegalArgumentException("deleteByDateRange 日期列非法: " + def.tableName() + "." + column);
        }
        if (isBlank(start) || isBlank(end)) {
            throw new IllegalArgumentException("deleteByDateRange 起止日期不能为空: " + def.tableName());
        }
        String table = def.tableName();
        withWriteLock(() -> {
            batchExecutor.executeInTransaction(TushareTableMapper.class,
                    mapper -> mapper.deleteByRange(table, column, start, end));
            return null;
        });
    }

    // ------------------------------------------------------------------ 校验

    private List<Map<String, Object>> validateRows(TableDef def, List<Map<String, Object>> rows,
                                                   List<String> keys) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> valid = new ArrayList<>(rows.size());
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                dropped++;
                continue;
            }
            boolean missingKey = false;
            for (String key : keys) {
                if (isBlank(row.get(key))) {
                    missingKey = true;
                    break;
                }
            }
            if (missingKey) {
                dropped++;
                continue;
            }
            valid.add(row);
        }
        if (dropped > 0) {
            log.warn("upsert 丢弃 {} 行：表 {} 幂等键列 {} 缺失或为空", dropped, def.tableName(), keys);
        }
        return valid;
    }

    /** 校验表名来自注册表，并返回（已校验的）列集合。 */
    private static List<String> requireRegistered(TableDef def) {
        if (def == null) {
            throw new IllegalArgumentException("TableDef 不能为空");
        }
        if (TushareTableRegistry.byTable(def.tableName()) == null) {
            throw new IllegalArgumentException("未注册的表名，拒绝写入: " + def.tableName());
        }
        List<String> columns = def.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("表没有任何列，拒绝写入: " + def.tableName());
        }
        return columns;
    }

    /** 幂等键必须全部落在列集合内（防止注册表与写入参数漂移）。 */
    private static List<String> requireKeysInColumns(TableDef def, List<String> columns) {
        List<String> keys = def.idempotencyKeys();
        for (String key : keys) {
            if (!columns.contains(key)) {
                throw new IllegalArgumentException("幂等键列不在表列集合内: " + def.tableName() + "." + key);
            }
        }
        return keys;
    }

    /** 整块删插的删除键必须非空且全部是本表列。 */
    private static List<String> requireBlockKeys(TableDef def, List<String> columns, List<String> blockKeyColumns) {
        if (blockKeyColumns == null || blockKeyColumns.isEmpty()) {
            throw new IllegalArgumentException("blockKeyColumns 不能为空: " + def.tableName());
        }
        List<String> keys = new ArrayList<>(blockKeyColumns.size());
        for (String key : blockKeyColumns) {
            if (key == null || !columns.contains(key)) {
                throw new IllegalArgumentException("blockKeyColumns 含非本表列: " + def.tableName() + "." + key);
            }
            keys.add(key);
        }
        return keys;
    }

    // ------------------------------------------------------------------ 值预处理

    private static List<Map<String, Object>> normalizeAll(List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(row.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                copy.put(entry.getKey(), normalize(entry.getValue()));
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        return value;
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof CharSequence text && text.toString().isBlank());
    }

    private static <T> T withWriteLock(java.util.function.Supplier<T> action) {
        writeLock.lock();
        try {
            return action.get();
        } finally {
            writeLock.unlock();
        }
    }
}
