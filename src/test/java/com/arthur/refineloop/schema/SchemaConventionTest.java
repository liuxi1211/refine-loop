package com.arthur.refineloop.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 《开发规范 02-数据库与SQL规范》的机器校验：解析 {@code db/schema.sql}（不建库、不连库）。
 *
 * <p>与 {@link SchemaSqliteTest} 的分工：后者校验「schema 能否在真实 SQLite 上建成、表/唯一键是否与契约一致」，
 * 本类校验「命名与结构是否守规范」。两者互补，缺一不可。
 *
 * <p>校验项：
 * <ol>
 *   <li>表名/索引名 snake_case、全局唯一、全部使用 {@code IF NOT EXISTS}；</li>
 *   <li>索引命名 {@code uk_<表名>[_<用途>]}/{@code idx_<表名>[_<用途>]}，长度 ≤ {@value #MAX_INDEX_NAME_LENGTH}；</li>
 *   <li>业务表：自增主键、{@code created_at}、至少一个唯一键、类型在矩阵内、金额/比率精度、禁止 {@code DATE}；</li>
 *   <li>枚举列宽度 {@code VARCHAR(16|32)}、布尔列 {@code TINYINT}（仅业务表）；</li>
 *   <li>每张表有表级注释；全库无 MySQL 残留方言（{@code AUTO_INCREMENT}/{@code ENGINE=}/{@code ON UPDATE CURRENT_TIMESTAMP}）。</li>
 * </ol>
 *
 * <p><b>例外清单</b>：每条都必须写理由与退出条件，并同步登记
 * {@code .claude/rules/附录-存量偏差登记.md}。新增例外 = 评审可见的技术债，不允许"顺手加一条"。
 *
 * <p>注意：本类只读文本，因此「注释覆盖率」只能校验到表级；列级单位/值域注释靠评审（规范 02 §10）。
 */
class SchemaConventionTest {

    private static final Path SCHEMA_PATH = Path.of("db", "schema.sql");
    private static final String BUSINESS_SECTION_MARKER = "第二部分：业务表";
    private static final int MAX_INDEX_NAME_LENGTH = 40;

    /** 表名：小写字母开头、snake_case。 */
    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    /** 允许的列类型（SQLite 沿用业务语义类型，见规范 02 §5）。 */
    private static final Pattern TYPE_WHITELIST = Pattern.compile(
            "^(INTEGER|INT|BIGINT|TINYINT|TEXT|DATETIME|DATE|CHAR\\(\\d+\\)|VARCHAR\\(\\d+\\)|DECIMAL\\(\\d+,\\d+\\))$");
    /** 业务表允许的 DECIMAL 精度：金额/数量 24,4；价格/比率 20,4。 */
    private static final Set<String> BUSINESS_DECIMALS = Set.of("DECIMAL(24,4)", "DECIMAL(20,4)");
    /** 枚举语义列（业务表）：宽度必须 16 或 32。 */
    private static final Set<String> ENUM_SUFFIXES =
            Set.of("_status", "_type", "_mode", "_result", "_stage", "_level", "_category");
    private static final Set<String> ENUM_EXACT_NAMES =
            Set.of("status", "result", "direction", "level", "category");

    /**
     * 业务表例外：表名 → 理由（必须与附录一致）。
     * <p>key 形如 {@code 表名:规则}，避免一张表的多个例外糊在一起。
     */
    private static final Map<String, String> BUSINESS_EXCEPTIONS = Map.of(
            "logic_tag_dict:id",
            "标签字典的自然键就是 tag_code（文本主键），加代理 id 无收益；见附录 E5",
            "rate_limit_counter:created_at",
            "限流计数是分钟级易失观测数据，仅保留 minute_slot；见附录 P1-11",
            "quality_check_log:created_at",
            "质量检查的业务时间就是 check_time，created_at 冗余；见附录 P1-11",
            "sys_config:created_at",
            "配置表只有 updated_at（首次写入即更新时间），补 created_at 需迁移存量；见附录 P1-11",
            "quality_check_log:unique",
            "一次检查可产生多行（按检查项），唯一性由 check_id 之外的业务语义保证；见附录 P1-11",
            "llm_usage.cost:decimal",
            "LLM 成本是美元单价级金额，需要 6 位小数；T17 成本控制时改为整数最小单位 + 币种；见附录 E12",
            "judgment.expire_at:date",
            "判断有效期沿用 T2 定稿的 DATE 类型（与判断比较逻辑一起改）；见附录 E6",
            "pipeline_run_item:unique",
            "流水线明细的唯一键待 T12 流水线引擎落地时按实际粒度确定；见附录 P1-12");

    private static SchemaModel model;
    private static int businessSectionLine;

    @BeforeAll
    static void parseSchema() {
        List<String> lines = readLines();
        businessSectionLine = findBusinessSectionLine(lines);
        model = new SchemaModel(parseTables(lines), parseIndexes(lines));
    }

    // ================================================================ 命名

    @Test
    @DisplayName("表名与索引名：snake_case、全局唯一、全部 IF NOT EXISTS")
    void namesAreSnakeCaseAndUnique() {
        assertThat(model.tables()).as("schema 必须至少有一张表").isNotEmpty();

        Set<String> duplicates = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableDef table : model.tables()) {
            assertThat(TABLE_NAME.matcher(table.name()).matches())
                    .as("表名 %s 必须是小写 snake_case", table.name()).isTrue();
            if (!seen.add(table.name())) {
                duplicates.add(table.name());
            }
        }
        assertThat(duplicates).as("表名不得重复").isEmpty();

        Set<String> indexNames = new LinkedHashSet<>();
        for (IndexDef index : model.indexes()) {
            assertThat(indexNames.add(index.name()))
                    .as("索引名 %s 全库唯一（SQLite 索引名是全局命名空间）", index.name()).isTrue();
            assertThat(index.name().length())
                    .as("索引名 %s 长度应 ≤%d", index.name(), MAX_INDEX_NAME_LENGTH)
                    .isLessThanOrEqualTo(MAX_INDEX_NAME_LENGTH);
            assertThat(index.table()).as("索引 %s 指向的表必须存在", index.name())
                    .isIn(model.tableNames());
        }
    }

    @Test
    @DisplayName("索引命名：uk_<表名> / idx_<表名> 前缀，禁止无表名的裸用途名")
    void indexPrefixContainsTableName() {
        List<String> violations = new ArrayList<>();
        for (IndexDef index : model.indexes()) {
            String expectedPrefix = (index.unique() ? "uk_" : "idx_") + index.table();
            boolean ok = index.name().equals(expectedPrefix)
                    || index.name().startsWith(expectedPrefix + "_");
            if (!ok) {
                violations.add(index.name() + " → 期望前缀 " + expectedPrefix);
            }
        }
        assertThat(violations).as("索引名必须带表名（规范 02 §8.1）").isEmpty();
    }

    @Test
    @DisplayName("业务表命名：单数、领域前缀、不出现复数后缀")
    void businessTableNamesAreSingular() {
        List<String> violations = new ArrayList<>();
        for (TableDef table : businessTables()) {
            if (table.name().endsWith("s") || table.name().endsWith("_list") || table.name().endsWith("_data")) {
                violations.add(table.name());
            }
        }
        assertThat(violations).as("业务表名必须单数（规范 02 §2）").isEmpty();
    }

    // ================================================================ 结构

    @Test
    @DisplayName("业务表结构：自增主键 + created_at + 至少一个唯一键")
    void businessTablesHavePrimaryKeyAuditAndUniqueKey() {
        List<String> violations = new ArrayList<>();
        for (TableDef table : businessTables()) {
            String idKey = table.name() + ":id";
            boolean hasAutoId = table.columns().stream()
                    .anyMatch(column -> column.name().equals("id")
                            && column.type().equalsIgnoreCase("INTEGER")
                            && column.definition().toUpperCase(Locale.ROOT).contains("PRIMARY KEY"));
            if (!hasAutoId && !hasPrimaryKey(table) && !BUSINESS_EXCEPTIONS.containsKey(idKey)) {
                violations.add(table.name() + " 缺少 id INTEGER PRIMARY KEY AUTOINCREMENT");
            }
            boolean hasCreatedAt = table.columns().stream().anyMatch(column -> column.name().equals("created_at"));
            if (!hasCreatedAt && !BUSINESS_EXCEPTIONS.containsKey(table.name() + ":created_at")) {
                violations.add(table.name() + " 缺少 created_at");
            }
            boolean hasUnique = hasPrimaryKey(table) || model.indexes().stream()
                    .anyMatch(index -> index.table().equals(table.name()) && index.unique());
            if (!hasUnique && !BUSINESS_EXCEPTIONS.containsKey(table.name() + ":unique")) {
                violations.add(table.name() + " 缺少唯一键（幂等键）");
            }
        }
        assertThat(violations).as("业务表结构不符合规范 02 §4/§7（例外见附录）").isEmpty();
    }

    @Test
    @DisplayName("每张表都有表级注释，且无重复的 CREATE TABLE")
    void everyTableHasTableComment() {
        List<String> missing = model.tables().stream()
                .filter(table -> !table.hasComment())
                .map(TableDef::name)
                .toList();
        assertThat(missing).as("每张表必须有表级注释（规范 02 §10）").isEmpty();
        assertThat(model.tables().stream().map(TableDef::name).toList())
                .as("表定义不得重复").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("没有任何表被遗漏索引（含全量替换的表）")
    void everyTableHasAtLeastOneIndex() {
        List<String> withoutIndex = model.tables().stream()
                .filter(table -> !hasPrimaryKey(table))
                .map(TableDef::name)
                .filter(name -> model.indexes().stream().noneMatch(index -> index.table().equals(name)))
                .toList();
        assertThat(withoutIndex).as("每张表至少一个索引（主键/唯一键/删除键），否则全表扫描").isEmpty();
    }

    // ================================================================ 类型

    @Test
    @DisplayName("列类型必须在类型矩阵内；业务表金额/比率精度受限，禁止 DATE")
    void columnTypesFollowTheMatrix() {
        List<String> unknownTypes = new ArrayList<>();
        List<String> decimalViolations = new ArrayList<>();
        List<String> dateViolations = new ArrayList<>();
        for (TableDef table : model.tables()) {
            boolean business = isBusiness(table);
            for (ColumnDef column : table.columns()) {
                String type = column.type().toUpperCase(Locale.ROOT);
                if (!TYPE_WHITELIST.matcher(type).matches()) {
                    unknownTypes.add(table.name() + "." + column.name() + " → " + column.type());
                    continue;
                }
                if (business && type.startsWith("DECIMAL(") && !BUSINESS_DECIMALS.contains(type)
                        && !BUSINESS_EXCEPTIONS.containsKey(table.name() + "." + column.name() + ":decimal")) {
                    decimalViolations.add(table.name() + "." + column.name() + " → " + column.type());
                }
                if (business && type.equals("DATE")
                        && !BUSINESS_EXCEPTIONS.containsKey(table.name() + "." + column.name() + ":date")) {
                    dateViolations.add(table.name() + "." + column.name());
                }
            }
        }
        assertThat(unknownTypes).as("列类型必须来自规范 02 §5 的类型矩阵").isEmpty();
        assertThat(decimalViolations).as("业务表金额/数量用 DECIMAL(24,4)、价格/比率用 DECIMAL(20,4)").isEmpty();
        assertThat(dateViolations).as("业务表禁用 DATE（统一 CHAR(8) yyyyMMdd）").isEmpty();
    }

    @Test
    @DisplayName("业务表枚举列 VARCHAR(16|32)，布尔列 TINYINT")
    void enumAndBooleanColumnsHaveStandardWidth() {
        List<String> enumViolations = new ArrayList<>();
        List<String> booleanViolations = new ArrayList<>();
        for (TableDef table : businessTables()) {
            for (ColumnDef column : table.columns()) {
                String name = column.name();
                String type = column.type().toUpperCase(Locale.ROOT);
                if (isEnumColumn(name) && !Set.of("VARCHAR(16)", "VARCHAR(32)").contains(type)) {
                    enumViolations.add(table.name() + "." + name + " → " + column.type());
                }
                if (isBooleanColumn(name) && !type.equals("TINYINT")) {
                    booleanViolations.add(table.name() + "." + name + " → " + column.type());
                }
            }
        }
        assertThat(enumViolations).as("枚举列统一 VARCHAR(16)（长码 32）").isEmpty();
        assertThat(booleanViolations).as("布尔列统一 TINYINT 0/1").isEmpty();
    }

    // ================================================================ 方言残留

    @Test
    @DisplayName("全库不得残留 MySQL 方言")
    void noMySqlResidue() {
        String ddl = model.ddlWithoutComments().toUpperCase(Locale.ROOT);
        assertThat(ddl).as("不得出现 AUTO_INCREMENT（用 INTEGER PRIMARY KEY AUTOINCREMENT）")
                .doesNotContain("AUTO_INCREMENT");
        assertThat(ddl).as("不得出现 ENGINE=").doesNotContain("ENGINE=");
        assertThat(ddl).as("不得出现 ON UPDATE CURRENT_TIMESTAMP（SQLite 不支持）")
                .doesNotContain("ON UPDATE CURRENT_TIMESTAMP");
        assertThat(ddl).as("不得出现 MySQL 的 COMMENT '...' 语法（SQLite 用 -- 注释）")
                .doesNotContain("COMMENT '");
    }

    // ================================================================ 解析

    private static List<String> readLines() {
        try {
            return Files.readAllLines(SCHEMA_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 db/schema.sql 失败（工作目录应为工程根目录）", e);
        }
    }

    private static int findBusinessSectionLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(BUSINESS_SECTION_MARKER)) {
                return i;
            }
        }
        throw new IllegalStateException("db/schema.sql 缺少业务表分段标记: " + BUSINESS_SECTION_MARKER);
    }

    private static List<TableDef> parseTables(List<String> lines) {
        Pattern create = Pattern.compile("^CREATE TABLE (IF NOT EXISTS )?([a-z0-9_]+) \\($");
        List<TableDef> tables = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = create.matcher(lines.get(i).trim());
            if (!matcher.matches()) {
                continue;
            }
            assertThat(matcher.group(1)).as("CREATE TABLE 必须带 IF NOT EXISTS: %s", matcher.group(2)).isNotNull();
            List<ColumnDef> columns = new ArrayList<>();
            boolean hasPrimaryKey = false;
            int j = i + 1;
            for (; j < lines.size(); j++) {
                String line = lines.get(j).trim();
                if (line.startsWith(");")) {
                    break;
                }
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                hasPrimaryKey = hasPrimaryKey || line.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
                if (line.startsWith("PRIMARY KEY")) {
                    continue;
                }
                String[] parts = line.split("\\s+", 3);
                if (parts.length >= 2) {
                    columns.add(new ColumnDef(parts[0], parts[1], line));
                }
            }
            String previous = i > 0 ? lines.get(i - 1).trim() : "";
            boolean hasComment = previous.startsWith("--") && !previous.replace("-", "").isBlank();
            tables.add(new TableDef(matcher.group(2), i, columns, hasComment, hasPrimaryKey));
        }
        return tables;
    }

    private static List<IndexDef> parseIndexes(List<String> lines) {
        Pattern create = Pattern.compile(
                "^CREATE (UNIQUE )?INDEX (IF NOT EXISTS )?([a-z0-9_]+) ON ([a-z0-9_]+) \\((.+)\\);?$");
        List<IndexDef> indexes = new ArrayList<>();
        for (String raw : lines) {
            Matcher matcher = create.matcher(raw.trim());
            if (!matcher.matches()) {
                continue;
            }
            assertThat(matcher.group(2)).as("CREATE INDEX 必须带 IF NOT EXISTS: %s", matcher.group(3)).isNotNull();
            indexes.add(new IndexDef(matcher.group(3), matcher.group(4), matcher.group(1) != null,
                    matcher.group(5)));
        }
        return indexes;
    }

    private static boolean isEnumColumn(String name) {
        return ENUM_EXACT_NAMES.contains(name) || ENUM_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static boolean isBooleanColumn(String name) {
        return name.startsWith("is_") || name.startsWith("has_") || name.endsWith("_flag");
    }

    private static boolean isBusiness(TableDef table) {
        return table.line() > businessSectionLine;
    }

    private static List<TableDef> businessTables() {
        return model.tables().stream().filter(SchemaConventionTest::isBusiness).toList();
    }

    // ================================================================ 模型

    private record ColumnDef(String name, String type, String definition) {
    }

    private record TableDef(String name, int line, List<ColumnDef> columns, boolean hasComment,
                            boolean hasPrimaryKey) {
    }

    private record IndexDef(String name, String table, boolean unique, String columns) {
    }

    private static boolean hasPrimaryKey(TableDef table) {
        return table.hasPrimaryKey();
    }

    private record SchemaModel(List<TableDef> tables, List<IndexDef> indexes, String ddlWithoutComments) {

        SchemaModel(List<TableDef> tables, List<IndexDef> indexes) {
            this(tables, indexes, readDdlWithoutComments());
        }

        Set<String> tableNames() {
            Set<String> names = new LinkedHashSet<>();
            tables.forEach(table -> names.add(table.name()));
            return names;
        }

        private static String readDdlWithoutComments() {
            try {
                StringBuilder ddl = new StringBuilder();
                for (String line : Files.readAllLines(SCHEMA_PATH, StandardCharsets.UTF_8)) {
                    int comment = line.indexOf("--");
                    ddl.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
                }
                return ddl.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("读取 db/schema.sql 失败", e);
            }
        }
    }
}
