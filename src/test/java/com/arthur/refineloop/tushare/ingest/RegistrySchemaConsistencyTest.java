package com.arthur.refineloop.tushare.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.TableGroup;
import com.arthur.refineloop.tushare.ingest.registry.TushareTableRegistry;

/**
 * 注册表 ↔ schema 一致性守护测试（契约 §4.4 第 5 条）：
 * 遍历 {@link TushareTableRegistry#ALL}，断言每张表实体的每个 {@code fields} 列都在 SQLite schema 中真实存在，
 * 且列顺序与列数与注册表完全一致——任何一方漂移都会让本测试失败。
 */
class RegistrySchemaConsistencyTest {

    /** 契约 §4.2 的 41 张表顺序（注册表顺序必须与此一致）。 */
    private static final List<String> EXPECTED_ORDER = List.of(
            "stock_basic", "trade_cal", "stock_namechange", "daily", "adj_factor", "daily_basic",
            "stock_moneyflow", "stock_stk_limit", "stock_weekly_quote", "stock_monthly_quote",
            "dividend", "fina_indicator", "income", "balancesheet", "cashflow", "forecast", "express",
            "fina_audit", "fina_mainbz", "disclosure_date", "top_list", "top_inst", "block_trade",
            "stock_suspend_d", "share_float", "pledge_stat", "pledge_detail", "repurchase",
            "top10_holders", "top10_floatholders", "stk_holdertrade", "stk_holdernumber", "report_rc",
            "index_basic", "index_daily", "index_weight", "sw_industry", "sw_industry_member", "hk_hold",
            "margin", "margin_detail");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
    }

    @Test
    void registryHas41TablesInContractOrder() {
        assertThat(TushareTableRegistry.ALL).hasSize(41);
        assertThat(TushareTableRegistry.ALL.stream().map(TableDef::tableName).toList())
                .containsExactlyElementsOf(EXPECTED_ORDER);
    }

    @Test
    void registryFieldsMatchSchemaColumnsExactly() {
        for (TableDef def : TushareTableRegistry.ALL) {
            List<String> schemaColumns = schemaColumns(def.tableName());
            assertThat(schemaColumns).as("schema 中表 %s 必须存在", def.tableName()).isNotEmpty();
            assertThat(def.columns()).as("表 %s 的 fields 列数必须与 schema 列数一致", def.tableName())
                    .hasSameSizeAs(schemaColumns);
            assertThat(schemaColumns).as("表 %s 的列名与顺序必须与注册表 fields 完全一致", def.tableName())
                    .containsExactlyElementsOf(def.columns());
            assertThat(def.fields()).as("表 %s 的 fields 串不得含空格", def.tableName())
                    .isEqualTo(String.join(",", def.columns()));
            for (String key : def.idempotencyKeys()) {
                assertThat(def.columns()).as("表 %s 的幂等键列 %s 必须在 fields 内", def.tableName(), key)
                        .contains(key);
            }
            if (def.latestDateColumn() != null) {
                assertThat(def.columns()).as("表 %s 的 latestDateColumn 必须在 fields 内", def.tableName())
                        .contains(def.latestDateColumn());
            }
            assertThat(def.columns()).as("表 %s 的 codeColumn 必须在 fields 内", def.tableName())
                    .contains(def.codeColumn());
        }
    }

    @Test
    void registryMetadataIsCoherent() {
        // 每日增量默认启用 = 除 hk_hold 外 40 张
        assertThat(TushareTableRegistry.dailyEnabled()).hasSize(40);
        assertThat(TushareTableRegistry.dailyEnabled()).extracting(TableDef::tableName)
                .doesNotContain("hk_hold");
        TableDef hkHold = TushareTableRegistry.byTable("hk_hold");
        assertThat(hkHold.dailyEnabledByDefault()).isFalse();
        assertThat(hkHold.options()).containsKey("disabledReason");

        // 分组数量：维表 6 / 行情 9 / 财务 10 / 事件 13 / 指数与市场 3
        assertThat(TushareTableRegistry.byGroup(TableGroup.DIMENSION)).hasSize(6);
        assertThat(TushareTableRegistry.byGroup(TableGroup.QUOTE)).hasSize(9);
        assertThat(TushareTableRegistry.byGroup(TableGroup.FINANCE)).hasSize(10);
        assertThat(TushareTableRegistry.byGroup(TableGroup.EVENT)).hasSize(13);
        assertThat(TushareTableRegistry.byGroup(TableGroup.INDEX_MARKET)).hasSize(3);

        // 查找语义
        assertThat(TushareTableRegistry.byTable("not_exists")).isNull();
        assertThat(TushareTableRegistry.byTable("daily").apiName()).isEqualTo("daily");
        assertThat(TushareTableRegistry.byTableAllowApi("daily").tableName()).isEqualTo("daily");
        assertThat(TushareTableRegistry.byTableAllowApi("namechange").tableName())
                .isEqualTo("stock_namechange");
        assertThat(TushareTableRegistry.byTableAllowApi("stk_weekly_monthly")).as("一对多 api 无法唯一确定").isNull();
        assertThat(TushareTableRegistry.byTable("stock_weekly_quote").apiName())
                .isEqualTo("stk_weekly_monthly");

        // 三张财务报表列数（契约 §5.2 明确：94 / 158 / 97）
        assertThat(TushareTableRegistry.byTable("income").columns()).hasSize(94);
        assertThat(TushareTableRegistry.byTable("balancesheet").columns()).hasSize(158);
        assertThat(TushareTableRegistry.byTable("cashflow").columns()).hasSize(97);
        assertThat(TushareTableRegistry.byTable("fina_indicator").fields())
                .endsWith(",or_yoy,debt_to_assets,dt_eps_yoy");
    }

    @Test
    void optionsAndSpecialMarkersMatchContract() {
        assertThat(TushareTableRegistry.byTable("stock_basic").options())
                .containsEntry("stock_status", "L,D,P");
        assertThat(TushareTableRegistry.byTable("trade_cal").options())
                .containsEntry("exchanges", "SSE,SZSE");
        assertThat(TushareTableRegistry.byTable("stock_weekly_quote").options())
                .containsEntry("freq", "week");
        assertThat(TushareTableRegistry.byTable("stock_monthly_quote").options())
                .containsEntry("freq", "month");
        assertThat(TushareTableRegistry.byTable("index_weight").options())
                .containsEntry("codeSource", "sw_industry");
        assertThat(TushareTableRegistry.byTable("index_daily").options())
                .containsEntry("codeSource", "index_basic");
        assertThat(TushareTableRegistry.byTable("income").options())
                .containsEntry("dedup", "keep_latest_ann_date");
        assertThat(TushareTableRegistry.byTable("sw_industry").options())
                .containsEntry("paramSrc", "SW2021");
        assertThat(TushareTableRegistry.byTable("sw_industry_member").options())
                .containsEntry("indexCodeLoop", "L1");
        assertThat(TushareTableRegistry.byTable("report_rc").options())
                .containsEntry("special", "single_page_recent_90d");
        assertThat(TushareTableRegistry.byTable("sw_industry_member").idempotencyKeys()).isEmpty();
        assertThat(TushareTableRegistry.byTable("sw_industry_member").writeMode())
                .isEqualTo(com.arthur.refineloop.tushare.ingest.model.WriteMode.FULL_REPLACE);
    }

    // ================================================================== helpers

    private List<String> schemaColumns(String table) {
        // SQLite 无 information_schema：PRAGMA table_info 返回 cid/name/type/notnull/dflt_value/pk，
        // 行顺序即 cid 顺序（建表列顺序），与注册表 fields 顺序直接比对。
        // 表名来自 TushareTableRegistry 白名单（[a-z0-9_]+），可安全拼接为 SQLite 字面量。
        List<String> columns = new ArrayList<>();
        jdbc.query("PRAGMA table_info('" + table + "')", rs -> {
            columns.add(rs.getString("name"));
        });
        return columns;
    }
}
