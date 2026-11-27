package com.arthur.refineloop.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.arthur.refineloop.support.SchemaTestSupport;

/**
 * T2 验收（契约 §4.4）：db/schema.sql 在 SQLite 全量执行成功，并静态校验
 * 表数量、34 张唯一键表 / 7 张整块删插表、业务表关键列、judgment 生成列唯一语义。
 * <p>SQLite 适配差异（相对 MySQL/H2 版本）：
 * <ul>
 *   <li>表清单来自 {@code sqlite_master}；</li>
 *   <li>SQLite 无表/列 COMMENT 语义，schema 把原 MySQL 表注释写成每个 {@code CREATE TABLE} 内首行
 *       {@code -- [原 MySQL 表注释] ...}，因此「每表有 COMMENT」改为解析 {@code db/schema.sql} 文本断言；</li>
 *   <li>唯一键用 {@code PRAGMA index_list} / {@code PRAGMA index_info} 读取（排除 {@code origin='pk'} 的主键自动索引）；</li>
 *   <li>列清单用 {@code PRAGMA table_info} 读取。</li>
 * </ul>
 *
 * <p>期望值按《T2~T5 开发协作契约》§4.2/§4.3/§4.5 与《手册》附录 C 硬编码（不复用注册表，避免同源漂移）。
 */
class SchemaSqliteTest {

    /** 41 张 Tushare 表（顺序 = 契约 §4.2）。 */
    private static final List<String> TUSHARE_TABLES = List.of(
            "stock_basic", "trade_cal", "stock_namechange", "daily", "adj_factor", "daily_basic",
            "stock_moneyflow", "stock_stk_limit", "stock_weekly_quote", "stock_monthly_quote",
            "dividend", "fina_indicator", "income", "balancesheet", "cashflow", "forecast", "express",
            "fina_audit", "fina_mainbz", "disclosure_date", "top_list", "top_inst", "block_trade",
            "stock_suspend_d", "share_float", "pledge_stat", "pledge_detail", "repurchase",
            "top10_holders", "top10_floatholders", "stk_holdertrade", "stk_holdernumber", "report_rc",
            "index_basic", "index_daily", "index_weight", "sw_industry", "sw_industry_member", "hk_hold",
            "margin", "margin_detail");

    /** 20 张业务表（契约 §4.2）。 */
    private static final List<String> BUSINESS_TABLES = List.of(
            "unit_fact", "judgment", "judgment_unit_ref", "logic_tag_dict", "pipeline_run",
            "pipeline_run_item", "pipeline_trigger", "report", "unit_version", "method_version",
            "method_version_changelog", "ingest_log", "rate_limit_counter", "llm_usage", "sys_config",
            "sys_user", "sys_user_session", "user_preference", "user_watchlist", "quality_check_log");

    /** 34 张应建 UNIQUE INDEX 的 Tushare 表 → 唯一键列（《手册》附录 C，index_weight 按契约修正）。 */
    private static final Map<String, List<String>> TUSHARE_UNIQUE_KEYS = Map.ofEntries(
            Map.entry("stock_basic", List.of("ts_code")),
            Map.entry("trade_cal", List.of("exchange", "cal_date")),
            Map.entry("stock_namechange", List.of("ts_code", "start_date")),
            Map.entry("daily", List.of("ts_code", "trade_date")),
            Map.entry("adj_factor", List.of("ts_code", "trade_date")),
            Map.entry("daily_basic", List.of("trade_date", "ts_code")),
            Map.entry("stock_moneyflow", List.of("ts_code", "trade_date")),
            Map.entry("stock_stk_limit", List.of("ts_code", "trade_date")),
            Map.entry("stock_weekly_quote", List.of("ts_code", "trade_date")),
            Map.entry("stock_monthly_quote", List.of("ts_code", "trade_date")),
            Map.entry("dividend", List.of("ts_code", "end_date", "ann_date")),
            Map.entry("fina_indicator", List.of("ts_code", "end_date")),
            Map.entry("income", List.of("ts_code", "end_date", "report_type")),
            Map.entry("balancesheet", List.of("ts_code", "end_date", "report_type")),
            Map.entry("cashflow", List.of("ts_code", "end_date", "report_type")),
            Map.entry("forecast", List.of("ts_code", "end_date", "ann_date")),
            Map.entry("express", List.of("ts_code", "end_date")),
            Map.entry("fina_audit", List.of("ts_code", "end_date", "ann_date")),
            Map.entry("fina_mainbz", List.of("ts_code", "end_date", "bz_item", "bz_code")),
            Map.entry("disclosure_date", List.of("ts_code", "end_date")),
            Map.entry("top_inst", List.of("trade_date", "ts_code", "exalter", "side")),
            Map.entry("block_trade", List.of("trade_date", "ts_code", "buyer", "seller")),
            Map.entry("stock_suspend_d", List.of("ts_code", "trade_date", "suspend_type")),
            Map.entry("share_float", List.of("ts_code", "float_date", "holder_name")),
            Map.entry("pledge_stat", List.of("ts_code", "end_date")),
            Map.entry("stk_holdertrade", List.of("ts_code", "ann_date", "holder_name", "in_de",
                    "begin_date", "close_date")),
            Map.entry("stk_holdernumber", List.of("ts_code", "end_date")),
            Map.entry("index_basic", List.of("ts_code")),
            Map.entry("index_daily", List.of("ts_code", "trade_date")),
            Map.entry("index_weight", List.of("index_code", "trade_date", "con_code")),
            Map.entry("sw_industry", List.of("index_code", "src")),
            Map.entry("hk_hold", List.of("trade_date", "code")),
            Map.entry("margin", List.of("exchange_id", "trade_date")),
            Map.entry("margin_detail", List.of("trade_date", "ts_code")));

    /** 7 张不建唯一键的整块删插表 → 删除键列（sw_industry_member 全量替换，无删除键）。 */
    private static final Map<String, List<String>> TUSHARE_DELETE_KEYS = Map.of(
            "top_list", List.of("trade_date", "ts_code", "name", "reason"),
            "sw_industry_member", List.of(),
            "pledge_detail", List.of("ts_code", "ann_date"),
            "repurchase", List.of("ts_code", "ann_date"),
            "top10_holders", List.of("ts_code", "end_date"),
            "top10_floatholders", List.of("ts_code", "end_date"),
            "report_rc", List.of("ts_code", "report_date", "org_name"));

    /** 17 张业务表关键列（契约 §4.5 / 架构 §5.2）。 */
    private static final Map<String, List<String>> BUSINESS_KEY_COLUMNS = Map.ofEntries(
            Map.entry("unit_fact", List.of("unit_id", "unit_version", "ts_code", "trade_date",
                    "payload", "data_status", "created_at")),
            Map.entry("judgment", List.of("judgment_id", "trade_date", "ts_code", "pipeline_id",
                    "report_id", "method_version", "direction", "horizon_days", "expire_at",
                    "confidence", "payload", "frozen", "superseded", "active_flag", "invalidated_at",
                    "created_at")),
            Map.entry("judgment_unit_ref", List.of("judgment_id", "unit_id", "unit_version",
                    "output_summary", "created_at")),
            Map.entry("logic_tag_dict", List.of("tag_code", "tag_name", "category", "description",
                    "active", "created_at")),
            Map.entry("pipeline_run", List.of("run_id", "pipeline_id", "trade_date", "trigger_type",
                    "status", "retry_wait_until", "item_total", "item_success", "item_failed",
                    "started_at", "finished_at", "error_msg", "created_at")),
            Map.entry("pipeline_run_item", List.of("run_id", "ts_code", "unit_id", "stage", "status",
                    "fact_summary", "degraded", "duration_ms", "error_msg", "created_at")),
            Map.entry("pipeline_trigger", List.of("trigger_id", "trigger_type", "pipeline_id",
                    "payload", "execute_at", "status", "consumed_at", "created_at")),
            Map.entry("report", List.of("report_no", "run_id", "ts_code", "trade_date", "report_type",
                    "title", "file_path", "status", "created_at")),
            Map.entry("unit_version", List.of("unit_id", "version", "change_note", "prompt_path",
                    "params", "status", "created_at", "activated_at")),
            Map.entry("method_version", List.of("version", "description", "status", "created_at",
                    "activated_at")),
            Map.entry("method_version_changelog", List.of("method_version", "unit_id", "unit_version",
                    "change_type", "change_detail", "created_at")),
            Map.entry("ingest_log", List.of("run_id", "table_name", "job_type", "range_params",
                    "status", "rows_fetched", "rows_written", "retry_count", "start_time", "end_time",
                    "duration_ms", "error_msg", "created_at")),
            Map.entry("rate_limit_counter", List.of("api_name", "minute_slot", "call_count")),
            Map.entry("llm_usage", List.of("run_id", "unit_id", "model", "prompt_tokens",
                    "completion_tokens", "total_tokens", "cost", "created_at")),
            Map.entry("sys_config", List.of("config_key", "config_value", "remark", "updated_at")),
            Map.entry("sys_user", List.of("username", "password_hash", "display_name", "role",
                    "active", "last_login_at", "remark", "created_at", "updated_at")),
            Map.entry("sys_user_session", List.of("user_id", "token_hash", "issued_at", "expires_at",
                    "last_used_at", "revoked", "created_at", "updated_at")),
            Map.entry("user_preference", List.of("user_id", "pref_key", "pref_value", "created_at",
                    "updated_at")),
            Map.entry("user_watchlist", List.of("user_id", "ts_code", "stock_name", "group_name",
                    "note", "sort_no", "created_at", "updated_at")),
            Map.entry("quality_check_log", List.of("check_time", "trigger_type", "result", "items",
                    "duration_ms")));

    /** schema 相对工程根目录的固定路径（Maven surefire 的工作目录 = 项目根）。 */
    private static final Path SCHEMA_PATH = Path.of("db", "schema.sql");

    /** SQLite 无表/列 COMMENT，schema 用该标记行承载原 MySQL 表注释。 */
    private static final String TABLE_COMMENT_MARKER = "[原 MySQL 表注释]";

    /** 匹配 {@code CREATE TABLE IF NOT EXISTS <表> (}。 */
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+[\"`]?([A-Za-z0-9_]+)[\"`]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** 61 张表（不含 SQLite 内部表）。 */
    private static final String TABLE_QUERY =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
    }

    // ================================================================== 1) 全量执行 + 表数/注释

    @Test
    void schemaExecutesAndCreates61TablesWithComments() {
        List<String> tables = tableNames();
        assertThat(tables).as("61 张表 = 41 Tushare + 20 业务").hasSize(61);
        List<String> expected = new ArrayList<>(TUSHARE_TABLES);
        expected.addAll(BUSINESS_TABLES);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(expected);

        SchemaDoc schemaDoc = parseSchema(readSchemaLines());
        assertThat(schemaDoc.tableComments().keySet())
                .as("db/schema.sql 文本应能解析出全部 61 张表的 CREATE TABLE").hasSize(61)
                .containsExactlyInAnyOrderElementsOf(expected);

        // 表注释：每张表 CREATE 内首行必须是非空的 `-- [原 MySQL 表注释] ...`
        List<String> tablesWithoutComment = new ArrayList<>();
        for (String table : tables) {
            if (textAfterTableCommentMarker(schemaDoc.tableComments().get(table)).isBlank()) {
                tablesWithoutComment.add(table);
            }
        }
        assertThat(tablesWithoutComment).as("每张表必须有非空表注释（-- [原 MySQL 表注释] ...）").isEmpty();

        // 说明行：41 张 Tushare 表为 `-- <表名>（api=...，<分组>）`；17 张业务表为 `-- <层>：<中文说明>`
        List<String> tablesWithoutDescription = new ArrayList<>();
        for (String table : tables) {
            String description = schemaDoc.descriptions().get(table);
            if (description == null || description.substring(2).trim().isEmpty()) {
                tablesWithoutDescription.add(table);
            }
        }
        assertThat(tablesWithoutDescription).as("每张表必须有一行表说明（-- <表名>（api=...）/ -- <层>：...）").isEmpty();
        for (String table : TUSHARE_TABLES) {
            String description = schemaDoc.descriptions().get(table);
            boolean match = description.startsWith("-- " + table + "（api=")
                    || description.startsWith("-- " + table + "(api=");
            assertThat(match).as("Tushare 表 %s 的说明行格式应为 `-- %s（api=...，分组）`，实际: %s",
                    table, table, description).isTrue();
        }
    }

    // ================================================================== 2) 34 张唯一键 / 7 张无唯一键

    @Test
    void tushareTablesHaveExpectedUniqueKeys() {
        Map<String, List<List<String>>> actual = uniqueIndexesByTable();

        long uniqueTableCount = TUSHARE_TABLES.stream().filter(actual::containsKey).count();
        assertThat(uniqueTableCount).as("Tushare 表应恰好 34 张建 UNIQUE INDEX").isEqualTo(34);
        List<String> tushareWithUnique = TUSHARE_TABLES.stream().filter(actual::containsKey).toList();
        assertThat(tushareWithUnique).as("无唯一键表必须恰好是契约规定的 7 张")
                .containsExactlyInAnyOrderElementsOf(TUSHARE_UNIQUE_KEYS.keySet());

        for (Map.Entry<String, List<String>> expected : TUSHARE_UNIQUE_KEYS.entrySet()) {
            String table = expected.getKey();
            assertThat(actual.get(table)).as("表 %s 应有且仅有一个 UNIQUE INDEX", table).hasSize(1);
            assertThat(actual.get(table).get(0)).as("表 %s 的唯一键列集合与顺序", table)
                    .containsExactlyElementsOf(expected.getValue());
        }
    }

    @Test
    void blockDeleteTablesHaveNoUniqueKeyButDeleteKeyColumns() {
        Map<String, List<List<String>>> actual = uniqueIndexesByTable();
        for (Map.Entry<String, List<String>> entry : TUSHARE_DELETE_KEYS.entrySet()) {
            String table = entry.getKey();
            assertThat(actual).as("整块删插表 %s 不得有 UNIQUE INDEX（同键可多行）", table)
                    .doesNotContainKey(table);
            List<String> columns = columnsOf(table);
            for (String keyColumn : entry.getValue()) {
                assertThat(columns).as("表 %s 的删除键列 %s 必须存在", table, keyColumn).contains(keyColumn);
            }
        }
        // 上述 7 张 + 34 张 = 41
        assertThat(TUSHARE_DELETE_KEYS.size() + TUSHARE_UNIQUE_KEYS.size()).isEqualTo(TUSHARE_TABLES.size());
    }

    // ================================================================== 3) 业务表关键列

    @Test
    void businessTablesHaveRequiredColumns() {
        for (Map.Entry<String, List<String>> entry : BUSINESS_KEY_COLUMNS.entrySet()) {
            List<String> columns = columnsOf(entry.getKey());
            assertThat(columns).as("业务表 %s 必须存在", entry.getKey()).isNotEmpty();
            for (String required : entry.getValue()) {
                assertThat(columns).as("业务表 %s 缺少列 %s", entry.getKey(), required).contains(required);
            }
        }
        // ingest_log（计划 Step1.4 原文）：唯一键为 run_id？不建唯一键；这里只校验 job_type/status 存在
        assertThat(columnsOf("ingest_log")).contains("job_type", "status", "range_params");
    }

    // ================================================================== 4) judgment 生成列唯一语义

    @Test
    void judgmentGeneratedColumnEnforcesSingleActiveRow() {
        String insertSql = "INSERT INTO judgment (judgment_id, trade_date, ts_code, pipeline_id, run_id, report_id, "
                + "method_version, direction, horizon_days, expire_at, confidence, payload, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        insertJudgment(insertSql, "J-20261121-P1-0001", "000001.SZ", 1L);
        assertThatThrownBy(() -> insertJudgment(insertSql, "J-20261121-P1-0002", "000001.SZ", 2L))
                .as("同 (ts_code, trade_date, pipeline_id) 且 superseded=0 必须唯一冲突")
                .isInstanceOf(DataAccessException.class);

        jdbc.update("UPDATE judgment SET superseded = 1 WHERE judgment_id = ?", "J-20261121-P1-0001");
        assertThat(jdbc.queryForObject(
                "SELECT active_flag FROM judgment WHERE judgment_id = ?", Integer.class,
                "J-20261121-P1-0001")).as("被取代行 active_flag 生成列应为 NULL").isNull();

        insertJudgment(insertSql, "J-20261121-P1-0003", "000001.SZ", 3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT active_flag FROM judgment WHERE judgment_id = ?", Integer.class,
                "J-20261121-P1-0003")).as("有效行 active_flag 生成列应为 1").isEqualTo(1);
    }

    // ================================================================== 5) SQLite 专项：WAL 生效

    @Test
    void journalModeIsWal() {
        String journalMode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        assertThat(journalMode).as("测试库 URL 的 journal_mode=WAL 必须生效").isEqualToIgnoringCase("wal");
    }

    // ================================================================== helpers

    private void insertJudgment(String insertSql, String judgmentId, String tsCode, long reportId) {
        jdbc.update(insertSql, judgmentId, "20261121", tsCode, "P1", "R-20261121-P1", reportId, "M1-v1", "bullish",
                20, "2026-12-11", "high", "{\"unit\":\"test\"}", "2026-11-21 10:00:00");
    }

    private List<String> tableNames() {
        return jdbc.queryForList(TABLE_QUERY, String.class);
    }

    /**
     * 列清单（按 cid 顺序）。
     *
     * <p>SQLite 用 {@code PRAGMA table_xinfo(<table>)} 取代 information_schema.columns：{@code table_info}
     * 只返回「普通列」，而 judgment.active_flag 是 {@code GENERATED ALWAYS AS ... STORED} 生成列，
     * 只有 {@code table_xinfo} 会把它（hidden=2/3）一并列出；本 schema 无虚表，故两者对普通表结果一致。
     */
    private List<String> columnsOf(String table) {
        List<String> columns = new ArrayList<>();
        jdbc.query("PRAGMA table_xinfo('" + table + "')", rs -> {
            columns.add(rs.getString("name"));
        });
        return columns;
    }

    /**
     * 返回 表 → 各 UNIQUE 索引的列列表（按索引内列顺序；多约束时有多项）。
     *
     * <p>SQLite：{@code PRAGMA index_list} 的 {@code origin} 区分来源——{@code 'c'} = CREATE INDEX、
     * {@code 'u'} = 列级 UNIQUE 约束、{@code 'pk'} = 主键自动索引。唯一键语义只认 {@code 'c'}/{@code 'u'}，
     * 排除 {@code 'pk'}（自增主键表/显式主键表会生成 {@code sqlite_autoindex_*}，与 MySQL 的 UNIQUE KEY 无关）。
     */
    private Map<String, List<List<String>>> uniqueIndexesByTable() {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        for (String table : tableNames()) {
            List<String> uniqueIndexNames = new ArrayList<>();
            jdbc.query("PRAGMA index_list('" + table + "')", rs -> {
                int unique = rs.getInt("unique");
                String origin = rs.getString("origin");
                if (unique == 1 && !"pk".equals(origin)) {
                    uniqueIndexNames.add(rs.getString("name"));
                }
            });
            if (uniqueIndexNames.isEmpty()) {
                continue;
            }
            List<List<String>> perTable = new ArrayList<>();
            for (String indexName : uniqueIndexNames) {
                List<String> indexColumns = new ArrayList<>();
                jdbc.query("PRAGMA index_info('" + indexName + "')", rs -> {
                    indexColumns.add(rs.getString("name"));
                });
                perTable.add(indexColumns);
            }
            result.put(table, perTable);
        }
        return result;
    }

    /** 读取 {@code db/schema.sql} 的全部行（工作目录 = 工程根）。 */
    private static List<String> readSchemaLines() {
        try {
            return Files.readAllLines(SCHEMA_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 db/schema.sql 失败（工作目录应为工程根目录）: "
                    + SCHEMA_PATH.toAbsolutePath(), e);
        }
    }

    /**
     * 扫描 schema 文本，提取每张表的「说明行」与「表注释行」。
     *
     * <p>说明行 = CREATE 之前最近的一行说明性注释（{@code -- <表名>（api=...）/ -- <层>：...}）；
     * 表注释行 = CREATE 前后紧邻的、含 {@code [原 MySQL 表注释]} 的注释行（当前 schema 放在
     * {@code CREATE TABLE ... (} 之后的首行，两种位置都兼容）。
     */
    private static SchemaDoc parseSchema(List<String> lines) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        Map<String, String> tableComments = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = CREATE_TABLE_PATTERN.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            String table = matcher.group(1);

            // 说明行：向上找最近的说明性注释（跳过空行；遇到非注释语句即停）
            for (int k = i - 1; k >= 0; k--) {
                String previous = lines.get(k).trim();
                if (previous.isEmpty()) {
                    continue;
                }
                if (isDescriptionComment(previous)) {
                    descriptions.put(table, previous);
                }
                break;
            }

            // 表注释行：CREATE 前连续注释 + CREATE 体内首列之前的注释，含标记行即命中
            String markerLine = null;
            for (int k = i - 1; k >= 0 && markerLine == null; k--) {
                String previous = lines.get(k).trim();
                if (previous.isEmpty()) {
                    continue;
                }
                if (!previous.startsWith("--")) {
                    break;
                }
                if (previous.contains(TABLE_COMMENT_MARKER)) {
                    markerLine = previous;
                }
            }
            for (int j = i + 1; j < lines.size() && markerLine == null; j++) {
                String bodyLine = lines.get(j).trim();
                if (bodyLine.isEmpty()) {
                    continue;
                }
                if (!bodyLine.startsWith("--")) {
                    break;
                }
                if (bodyLine.contains(TABLE_COMMENT_MARKER)) {
                    markerLine = bodyLine;
                }
            }
            if (markerLine != null) {
                tableComments.put(table, markerLine);
            }
        }
        return new SchemaDoc(descriptions, tableComments);
    }

    /** 判断是否为表说明注释行（排除分隔线、表注释标记行与空注释）。 */
    private static boolean isDescriptionComment(String trimmedLine) {
        if (!trimmedLine.startsWith("--")) {
            return false;
        }
        String content = trimmedLine.substring(2).trim();
        if (content.isEmpty() || content.contains(TABLE_COMMENT_MARKER)) {
            return false;
        }
        return !content.matches("=+");
    }

    /** 取表注释标记之后的内容（无标记行时返回空串）。 */
    private static String textAfterTableCommentMarker(String markerLine) {
        if (markerLine == null) {
            return "";
        }
        int markerIndex = markerLine.indexOf(TABLE_COMMENT_MARKER);
        return markerIndex < 0 ? "" : markerLine.substring(markerIndex + TABLE_COMMENT_MARKER.length()).trim();
    }

    /** schema 文本解析结果：表 → 说明行、表 → 表注释标记行。 */
    private record SchemaDoc(Map<String, String> descriptions, Map<String, String> tableComments) {
    }
}
