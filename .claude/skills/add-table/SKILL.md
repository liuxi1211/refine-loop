---
name: add-table
description: 在 refine-loop 新增或修改数据表（含 41 张 Tushare 镜像表）时使用。给出 schema→实体→Mapper→注册表→单测→删库重建的同步清单，避免表结构与实体/注册表/测试不一致。
---

# 新增 / 修改一张表

`db/schema.sql` 是表结构唯一事实源；规范：`.claude/rules/02-数据库与SQL规范.md`。以下 6 步必须在同一次提交完成。

1. **schema**：`db/schema.sql` 对应段落追加 `CREATE TABLE IF NOT EXISTS`。
   业务表照模板：`id INTEGER PRIMARY KEY AUTOINCREMENT` → 业务列 → `created_at` → `updated_at` → 末尾 `uk_<table>`（自然键）；
   表级注释写「中文名 ｜ 唯一键 ｜ 单位口径」；索引 `uk_/idx_<表名>[_<用途>]`，每表 ≤3 个并注明对应查询。
2. **实体**：`data/entity/<表名>Entity`（`@TableName` + `@TableId`，新表继承 `BaseEntity`，`@Getter @Setter`，禁 `@Data`）。
3. **Mapper**：`data/mapper/XxxMapper extends BaseMapper<XxxEntity>`，只放非 CRUD 查询；**service 里不写 SQL**。
4. **镜像表额外**：在 `tushare/ingest/registry/TushareTableRegistry` 登记 fields / 幂等键 / 限流 / 单位口径，
   与 schema 逐列一致（`RegistrySchemaConsistencyTest` 用 `PRAGMA table_info` 比对）。
5. **单测**：`SchemaSqliteTest` 表清单（新增表时）；`SchemaConventionTest` 自动校验命名/类型/审计列；
   业务服务补读写用例（真实 SQLite 临时库）；用于下拉/标签的枚举登记 `DictEnum`。
6. **重建**：1.0 前删库重建（删 `data/refine_loop.db` 及 `-wal`/`-shm`，启动自动建表）；1.0 后写 `db/migration/V<序号>__<描述>.sql`。

## 默认必须满足

唯一键、`created_at`/`updated_at`、无外键、无 `DATE`/`FLOAT`/无长度 `VARCHAR`；金额 `DECIMAL(24,4)`、价格/比率 `DECIMAL(20,4)`；
枚举 `VARCHAR(16)`、布尔 `TINYINT`。确有例外 → 在 `SchemaConventionTest` 里写理由并登记
`.claude/rules/附录-存量偏差登记.md`（含退出条件）。

## 自检

```powershell
mvn -B test -Dtest='SchemaSqliteTest,SchemaConventionTest,RegistrySchemaConsistencyTest'
mvn -B test
```
