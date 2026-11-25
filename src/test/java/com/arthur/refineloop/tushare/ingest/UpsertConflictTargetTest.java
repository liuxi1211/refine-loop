package com.arthur.refineloop.tushare.ingest;

import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.service.IngestTableWriter;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.tushare.ingest.model.WriteMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.arthur.refineloop.data.mapper.TushareTableMapper;

/**
 * SQLite 迁移专项守护：UPSERT 的 {@code ON CONFLICT (幂等键)} 必须能匹配到表上的唯一索引。
 *
 * <p>MySQL 的 {@code ON DUPLICATE KEY UPDATE} 与唯一键列顺序无关，但 SQLite 的
 * {@code ON CONFLICT (列清单)} 要求「列集合与顺序」与某个 UNIQUE 索引/主键一致，否则报
 * {@code ON CONFLICT clause does not match any PRIMARY KEY or UNIQUE constraint}。
 * 因此注册表幂等键与 schema 唯一索引必须逐列同序——本测对全部 UPSERT 表做真实写入验证，
 * 防止只靠字符串断言漏掉顺序漂移。
 */
class UpsertConflictTargetTest {

    private JdbcTemplate jdbc;
    private IngestTableWriter writer;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        writer = new IngestTableWriter(SchemaTestSupport.batchExecutor(jdbc));
    }

    @Test
    void 全部UPSERT表的幂等键与唯一索引列集合顺序一致() {
        Map<String, List<List<String>>> uniqueIndexes = uniqueIndexesByTable();
        List<String> upsertTables = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (TableDef def : TushareTableRegistry.ALL) {
            if (def.writeMode() != WriteMode.UPSERT) {
                continue;
            }
            upsertTables.add(def.tableName());
            List<List<String>> candidates = uniqueIndexes.getOrDefault(def.tableName(), List.of());
            List<String> keys = def.idempotencyKeys();
            boolean matched = candidates.stream().anyMatch(columns -> columns.equals(keys));
            if (!matched) {
                mismatches.add(def.tableName() + " keys=" + keys + " 索引=" + candidates);
            }
        }
        assertThat(upsertTables).as("注册表应有 UPSERT 表").isNotEmpty();
        assertThat(mismatches).as("以下表的幂等键与唯一索引列不一致/顺序不一致，SQLite ON CONFLICT 会失败")
                .isEmpty();
    }

    @Test
    void 每张UPSERT表按幂等键写入两次仍只有一行() {
        List<String> failures = new ArrayList<>();
        for (TableDef def : TushareTableRegistry.ALL) {
            if (def.writeMode() != WriteMode.UPSERT) {
                continue;
            }
            try {
                Map<String, Object> row = keyOnlyRow(def);
                int first = writer.writeUpsert(def, List.of(row));
                int second = writer.writeUpsert(def, List.of(row));
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + def.tableName(), Long.class);
                if (first != 1 || second != 1 || count == null || count != 1L) {
                    failures.add(def.tableName() + " first=" + first + " second=" + second + " count=" + count);
                }
            } catch (RuntimeException e) {
                failures.add(def.tableName() + " 抛异常: " + e.getMessage());
            }
        }
        assertThat(failures).as("UPSERT 幂等性验证失败（重跑不应翻倍/不应报 ON CONFLICT 不匹配）").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** 只填幂等键的最小行（其余列落 NULL）。 */
    private static Map<String, Object> keyOnlyRow(TableDef def) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : def.idempotencyKeys()) {
            row.put(key, sampleValue(key));
        }
        return row;
    }

    private static Object sampleValue(String column) {
        return switch (column) {
            case "ts_code", "con_code" -> "000001.SZ";
            case "index_code", "l1_code", "l2_code", "l3_code" -> "000300.SH";
            case "code" -> "00001";
            case "exchange", "exchange_id" -> "SSE";
            case "src" -> "SW2021";
            case "trade_date", "cal_date", "end_date", "ann_date", "start_date", "float_date",
                 "begin_date", "close_date", "report_date" -> "20261124";
            case "report_type", "suspend_type", "side", "in_de" -> "1";
            case "holder_name", "exalter", "buyer", "seller", "name", "reason", "bz_item", "bz_code",
                 "org_name", "level", "industry_code" -> "k_" + column;
            default -> "k_" + column;
        };
    }

    /** 表 → 各唯一索引的列列表（只取 origin=c/u 的唯一索引，排除主键自动索引与自增表主键）。 */
    private Map<String, List<List<String>>> uniqueIndexesByTable() {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", String.class);
        for (String table : tables) {
            List<Map<String, Object>> indexes = jdbc.queryForList("PRAGMA index_list('" + table + "')");
            List<List<String>> uniqueColumns = new ArrayList<>();
            for (Map<String, Object> index : indexes) {
                Object unique = index.get("unique");
                Object origin = index.get("origin");
                boolean isUnique = unique instanceof Number number && number.intValue() == 1;
                String originValue = origin == null ? "" : String.valueOf(origin);
                if (!isUnique || "pk".equals(originValue)) {
                    continue;
                }
                String indexName = String.valueOf(index.get("name"));
                List<String> columns = new ArrayList<>();
                for (Map<String, Object> info : jdbc.queryForList("PRAGMA index_info('" + indexName + "')")) {
                    columns.add(String.valueOf(info.get("name")));
                }
                uniqueColumns.add(columns);
            }
            if (!uniqueColumns.isEmpty()) {
                result.put(table, uniqueColumns);
            }
        }
        return result;
    }
}
