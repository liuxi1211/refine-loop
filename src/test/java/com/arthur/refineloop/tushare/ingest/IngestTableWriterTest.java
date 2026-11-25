package com.arthur.refineloop.tushare.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.model.IngestStrategy;
import com.arthur.refineloop.tushare.ingest.service.IngestTableWriter;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.TableGroup;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;
import com.arthur.refineloop.tushare.ingest.model.WriteMode;
import com.arthur.refineloop.data.mapper.TushareTableMapper;

/**
 * 通用落库器验收（契约 §5.3）：SQLite 下验证 upsert 幂等重跑不翻倍、replaceAll 覆盖、整块删插替换旧块、
 * 非法表/列被拒、缺键行丢弃、Float/Double → BigDecimal、500 行/批边界。
 */
class IngestTableWriterTest {

    private JdbcTemplate jdbc;
    private IngestTableWriter writer;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        writer = new IngestTableWriter(SchemaTestSupport.batchExecutor(jdbc));
    }

    // ================================================================== 1) UPSERT 幂等

    @Test
    void upsertIsIdempotentAndUpdatesNonKeyColumns() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        List<Map<String, Object>> first = List.of(
                row("ts_code", "000001.SZ", "trade_date", "20261121", "close", 9.85, "vol", 1234.5, "amount", 567.8),
                row("ts_code", "000002.SZ", "trade_date", "20261121", "close", 20.0, "vol", 999.0, "amount", 100.0));
        assertThat(writer.writeUpsert(daily, first)).isEqualTo(2);
        assertThat(count("daily")).isEqualTo(2);

        List<Map<String, Object>> rerun = List.of(
                row("ts_code", "000001.SZ", "trade_date", "20261121", "close", 10.5, "vol", 1.0, "amount", 2.0),
                row("ts_code", "000002.SZ", "trade_date", "20261121", "close", 21.0, "vol", 3.0, "amount", 4.0));
        assertThat(writer.writeUpsert(daily, rerun)).isEqualTo(2);
        assertThat(count("daily")).as("幂等重跑不得翻倍").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT close FROM daily WHERE ts_code = ?", BigDecimal.class, "000001.SZ"))
                .as("非键列应被新值覆盖").isEqualByComparingTo("10.5000");
        assertThat(jdbc.queryForObject("SELECT vol FROM daily WHERE ts_code = ?", BigDecimal.class, "000001.SZ"))
                .isEqualByComparingTo("1.0000");
    }

    @Test
    void upsertDropsRowsWithMissingOrBlankIdempotencyKeys() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        List<Map<String, Object>> rows = List.of(
                row("trade_date", "20261121", "close", 1.0),
                row("ts_code", "", "trade_date", "20261121", "close", 2.0),
                row("ts_code", "000001.SZ", "trade_date", "20261121", "close", 3.0));
        assertThat(writer.writeUpsert(daily, rows)).as("只写入键完整的 1 行").isEqualTo(1);
        assertThat(count("daily")).isEqualTo(1);
    }

    @Test
    void upsertCrossesBatchBoundary() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1203; i++) {
            rows.add(row("ts_code", String.format("T%05d.SZ", i), "trade_date", "20261121", "close", i));
        }
        assertThat(writer.writeUpsert(daily, rows)).isEqualTo(1203);
        assertThat(count("daily")).isEqualTo(1203);
    }

    // ================================================================== 2) FULL_REPLACE

    @Test
    void replaceAllOverwritesOldRows() {
        TableDef stockBasic = TushareTableRegistry.byTable("stock_basic");
        assertThat(writer.replaceAll(stockBasic, List.of(
                row("ts_code", "000001.SZ", "name", "平安银行"),
                row("ts_code", "600519.SH", "name", "贵州茅台")))).isEqualTo(2);
        assertThat(count("stock_basic")).isEqualTo(2);

        assertThat(writer.replaceAll(stockBasic, List.of(
                row("ts_code", "000001.SZ", "name", "平安银行")))).isEqualTo(1);
        assertThat(count("stock_basic")).as("全量替换后旧行不保留").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT name FROM stock_basic WHERE ts_code = ?", String.class, "600519.SH"))
                .as("旧行应已被删除").isEmpty();
    }

    @Test
    void replaceAllWithEmptyRowsDoesNotWipeTable() {
        TableDef stockBasic = TushareTableRegistry.byTable("stock_basic");
        writer.replaceAll(stockBasic, List.of(row("ts_code", "000001.SZ", "name", "平安银行")));
        assertThat(writer.replaceAll(stockBasic, List.of())).as("空结果不替换，保护数据").isZero();
        assertThat(count("stock_basic")).isEqualTo(1);
    }

    // ================================================================== 3) BLOCK_DELETE_INSERT

    @Test
    void deleteAndInsertBlockReplacesWholeBlock() {
        TableDef holders = TushareTableRegistry.byTable("top10_holders");
        List<String> keys = holders.idempotencyKeys();
        List<Map<String, Object>> firstBlock = List.of(
                row("ts_code", "000001.SZ", "end_date", "20260930", "holder_name", "股东A", "hold_amount", 100),
                row("ts_code", "000001.SZ", "end_date", "20260930", "holder_name", "股东B", "hold_amount", 200));
        assertThat(writer.deleteAndInsertBlock(holders, keys, firstBlock)).isEqualTo(2);
        assertThat(count("top10_holders")).isEqualTo(2);

        List<Map<String, Object>> newBlock = List.of(
                row("ts_code", "000001.SZ", "end_date", "20260930", "holder_name", "股东C", "hold_amount", 300));
        assertThat(writer.deleteAndInsertBlock(holders, keys, newBlock)).isEqualTo(1);
        assertThat(count("top10_holders")).as("同键旧块应被整体替换").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT holder_name FROM top10_holders", String.class)).containsExactly("股东C");
    }

    @Test
    void deleteAndInsertBlockKeepsOtherBlocks() {
        TableDef topList = TushareTableRegistry.byTable("top_list");
        List<String> keys = topList.idempotencyKeys();
        writer.deleteAndInsertBlock(topList, keys, List.of(
                row("trade_date", "20261121", "ts_code", "000001.SZ", "name", "平安银行", "reason", "涨幅偏离",
                        "close", 9.85)));
        writer.deleteAndInsertBlock(topList, keys, List.of(
                row("trade_date", "20261120", "ts_code", "000001.SZ", "name", "平安银行", "reason", "涨幅偏离",
                        "close", 9.5)));
        assertThat(count("top_list")).isEqualTo(2);
        // 同删除键（trade_date,ts_code,name,reason）的块才替换；这里 reason 不变、close 变更
        writer.deleteAndInsertBlock(topList, keys, List.of(
                row("trade_date", "20261121", "ts_code", "000001.SZ", "name", "平安银行", "reason", "涨幅偏离",
                        "close", 9.9)));
        assertThat(count("top_list")).as("只替换同键块，其他块保留").isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT reason FROM top_list ORDER BY trade_date", String.class))
                .containsExactly("涨幅偏离", "涨幅偏离");
        assertThat(jdbc.queryForObject(
                "SELECT close FROM top_list WHERE trade_date = '20261121'", BigDecimal.class))
                .isEqualByComparingTo("9.9");
    }

    @Test
    void deleteAndInsertBlockRejectsInconsistentBlockRows() {
        TableDef holders = TushareTableRegistry.byTable("top10_holders");
        List<Map<String, Object>> inconsistent = List.of(
                row("ts_code", "000001.SZ", "end_date", "20260930", "holder_name", "A"),
                row("ts_code", "000001.SZ", "end_date", "20260630", "holder_name", "B"));
        assertThatThrownBy(() -> writer.deleteAndInsertBlock(holders, List.of("ts_code", "end_date"), inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("键值一致");
        assertThat(count("top10_holders")).isZero();
    }

    // ================================================================== 4) 白名单/注入防护

    @Test
    void rejectsUnregisteredTable() {
        TableDef fake = fakeDef("not_registered", "ts_code");
        assertThatThrownBy(() -> writer.writeUpsert(fake, List.of(row("ts_code", "000001.SZ"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
        assertThatThrownBy(() -> writer.replaceAll(fake, List.of(row("ts_code", "000001.SZ"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
        assertThatThrownBy(() -> writer.deleteAndInsertBlock(fake, List.of("ts_code"),
                List.of(row("ts_code", "000001.SZ"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
    }

    @Test
    void rejectsInjectedColumnNames() {
        assertThatThrownBy(() -> new TableDef("daily", "daily", "日线行情", TableGroup.QUOTE,
                "ts_code,trade_date; DROP TABLE daily", IngestStrategy.PER_TRADE_DATE, WriteMode.UPSERT,
                List.of("ts_code"), 10000, 10, 500, "trade_date", "ts_code", "-", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法列名");
        assertThatThrownBy(() -> new TableDef("daily", "daily", "日线行情", TableGroup.QUOTE,
                "ts_code,trade_date", IngestStrategy.PER_TRADE_DATE, WriteMode.UPSERT,
                List.of("trade_date AND 1=1"), 10000, 10, 500, "trade_date", "ts_code", "-", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法幂等键列");
    }

    @Test
    void rejectsBlockKeyColumnsNotInTable() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        List<Map<String, Object>> rows = List.of(row("ts_code", "000001.SZ", "trade_date", "20261121"));
        assertThatThrownBy(() -> writer.deleteAndInsertBlock(daily, List.of("evil_column"), rows))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非本表列");
        assertThatThrownBy(() -> writer.deleteAndInsertBlock(daily, List.of(), rows))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    // ================================================================== 5) 值类型与空值

    @Test
    void convertsFloatAndDoubleToBigDecimal() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        writer.writeUpsert(daily, List.of(
                row("ts_code", "000001.SZ", "trade_date", "20261121", "close", 9.85d, "vol", 1234.5f)));
        assertThat(jdbc.queryForObject("SELECT close FROM daily WHERE ts_code = ?", BigDecimal.class, "000001.SZ"))
                .isEqualByComparingTo("9.85");
        assertThat(jdbc.queryForObject("SELECT vol FROM daily WHERE ts_code = ?", BigDecimal.class, "000001.SZ"))
                .isEqualByComparingTo("1234.5");
    }

    @Test
    void missingColumnsAreWrittenAsNullAndBlankStringsAsNull() {
        TableDef daily = TushareTableRegistry.byTable("daily");
        writer.writeUpsert(daily, List.of(row("ts_code", "000001.SZ", "trade_date", "20261121",
                "close", "", "vol", null, "open", 1.0)));
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT close, vol, open, high FROM daily WHERE ts_code = ?", "000001.SZ");
        assertThat(stored.get("close")).isNull();
        assertThat(stored.get("vol")).isNull();
        assertThat(stored.get("high")).as("行里缺失的列按 NULL 落库").isNull();
        // SQLite 动态类型：DECIMAL 列按存储类取值（1.0 落库后可能是 INTEGER 1 / REAL 1.0），
        // 因此用 BigDecimal 读取（JDBC 负责数值转换）而不是断言 Java 侧的具体包装类型
        assertThat(jdbc.queryForObject("SELECT open FROM daily WHERE ts_code = ?", BigDecimal.class, "000001.SZ"))
                .isEqualByComparingTo("1");
    }

    // ================================================================== helpers

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private static TableDef fakeDef(String tableName, String fields) {
        return new TableDef(tableName, tableName, "测试表", TableGroup.DIMENSION, fields,
                IngestStrategy.ONE_SHOT, WriteMode.UPSERT, List.of(fields), 100, 1, 100,
                fields, fields, "-", Map.of());
    }
}
