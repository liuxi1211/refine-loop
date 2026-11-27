---
paths:
  - "db/**"
  - "src/main/java/com/arthur/refineloop/data/**"
  - "src/test/java/com/arthur/refineloop/schema/**"
---

> 本文件于 2026-11-25 从旧规范目录迁入 `.claude/rules/`，此后**此处为唯一载体**（规范正文只此一份，`docs/` 只放业务与设计文档）。

# 02 数据库与 SQL 规范

> 引擎：SQLite 3（sqlite-jdbc，WAL）。事实源：仓库根 `db/schema.sql`（构建期复制进 classpath，启动期由 `SqliteSchemaInitializer` 幂等执行）。
> 强制等级：命名/类型/结构类规则为 `[L2]`（`SchemaConventionTest` 解析 `db/schema.sql` 校验 + `SchemaSqliteTest` 真实建表校验）；SQL 书写类为 `[L3]` + 少量 `[L2]`（ArchUnit 禁止 `JdbcTemplate`）。

---

## 1. 两类表，两套规则（先分清再动手）

| | **镜像表**（41 张） | **业务表**（20 张） |
|---|---|---|
| 来源 | Tushare 上游契约，字段由《Tushare 接口对接手册》附录 C 定义 | 本项目自设计 |
| 表名/列名 | **继承上游语义，禁止"美化"改名**（`stock_*`/`stk_*`/`sw_*` 等历史前缀保留） | 受 §3 全部命名规则约束 |
| 主键 | 无 `id`；以 `UNIQUE INDEX` 承担自然键（幂等键） | `id INTEGER PRIMARY KEY AUTOINCREMENT` + `uk_<table>` 自然键 |
| 审计列 | **不需要**（批次级时间由 `ingest_log` 记录） | 必须有 `created_at`（可变的再加 `updated_at`） |
| 单位口径 | 每张表在表注释里写清（`vol=手`、`amount=千元`…） | 同左，且列名尽量带单位后缀 |
| 约束目标 | 幂等（`ON CONFLICT` 冲突目标）可执行；索引命名合规 | 幂等 + 生命周期 + 可审计 |
| 谁改得动 | 只有"契约变更"流程（手册 + 注册表 + 单测三处同步） | 本项目自主决定（走 §12 流程） |

> 判断口径：表名出现在 `TushareTableRegistry.ALL` 里的就是镜像表。新增表时先问：这是上游数据，还是本项目产生的数据？

---

## 2. 表命名

| 规则 | 正例 | 反例 |
|---|---|---|
| 全小写 `snake_case`，**单数** | `judgment`、`pipeline_run`、`stock_basic` | `Judgments`、`pipelineRuns` |
| 业务表用「领域前缀 + 实体」，领域前缀取自总纲的域划分 | `judgment_unit_ref`、`method_version_changelog`、`quality_check_log` | `ref`、`t1`、`data_table` |
| 系统/配置/观测表用 `sys_` 前缀或明确的技术名 | `sys_config`、`sys_user`、`ingest_log`、`rate_limit_counter`、`llm_usage` | `config`、`log` |
| 不使用 SQL 关键字、不复用引擎内部名（`sqlite_*`） | `report`、`unit_fact` | `order`、`group`、`index` |
| 不出现项目名/版本号/时间戳 | `daily` | `daily_v2`、`daily_2026` |
| 关联表命名 `<主体>_<从属>` | `judgment_unit_ref`、`pipeline_run_item` | `refs`、`link_table` |

**存量豁免**：`top10_holders`、`top10_floatholders`（上游复数名）、`logic_tag_dict`（文本主键表）已在[附录](附录-存量偏差登记.md)登记，新表不得效仿。

---

## 3. 字段命名

### 3.1 基本规则

- 全小写 `snake_case`；单词完整，**禁止无解释的缩写**（`qty`→`quantity`、`pct` 仅允许作为 `_pct` 后缀）。
- 布尔用 `is_` / `has_` / `enable_` 语义前缀或 `_flag` 后缀，取值 `0/1`：`frozen` → `is_frozen`（新表），存量 `frozen` 保留。
- 主键统一 `id`；业务编号用 `_id`（字符串业务号）或 `_no`（人读单号）；配置键 `_key`；编码 `_code`。
- 时间列后缀：时间点 `_at`（`created_at`/`started_at`/`invalidated_at`）、业务日期 `_date`（`trade_date`/`ann_date`，`CHAR(8)`）、耗时 `_ms`、天数 `_days`。
- 引用列 `_id`，并写明引用目标（注释里）；**不建数据库外键**（§9.3）。
- 不使用 `type`/`status` 之外的泛化名（`result`/`flag`/`value`/`data` 需加限定前缀：`quality_result`、`active_flag`）。
- 单位进列名或进注释（§5）：`amount_wan`（万元）/`volume_lot`（手）用于**新增**金额数量列；镜像表保持上游列名 + 注释。

### 3.2 常用枚举列的一等公民

`status`、`*_type`、`*_mode`、`*_stage`、`*_level`、`*_category`、`*_result`、`data_status` 这类列，**列名必须能一眼看出语义域**：

| 好 | 差 | 原因 |
|---|---|---|
| `job_status`（接入作业状态） | `status`（表里还有别的状态时） | 同一张表出现多个状态列时无法区分 |
| `trigger_type` | `type` | — |
| `quality_result` | `result` | — |
| `write_mode` | `mode` | — |

单状态列（表里只有一个状态语义）允许叫 `status`：`pipeline_run.status`、`unit_version.status` ✓。

### 3.3 禁用/慎用

| 项 | 约定 |
|---|---|
| `change`（MySQL 保留字） | 仅 `daily.change` 等镜像表保留；业务表禁用 |
| `open`/`close`/`high`/`low` | 镜像表行情列保留；业务表用 `open_price` 等 |
| 拼音/中文列名 | 禁止（`cnspell` 属镜像表上游字段，豁免） |
| 列名带表名（`daily_close`） | 禁止 |
| 同表内同义异名（`create_time` vs `created_at`） | 禁止 |

---

## 4. 业务表结构模板（复制即用）

```sql
-- judgment：判断存档（唯一键 ts_code+trade_date+pipeline_id 有效行；无金额列）
CREATE TABLE IF NOT EXISTS judgment (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  judgment_id    VARCHAR(40)  NOT NULL,               -- 业务编号 J-20261122-P1-0001
  ts_code        VARCHAR(12)  NOT NULL,               -- 标的代码
  trade_date     CHAR(8)      NOT NULL,               -- 交易日 yyyyMMdd
  pipeline_id    VARCHAR(8)   NOT NULL,               -- 流水线编号
  run_id         VARCHAR(40)  NOT NULL,               -- 产出该判断的 pipeline_run.run_id
  report_id      BIGINT       NOT NULL,               -- 引用 report.id（应用层校验）
  method_version VARCHAR(20)  NOT NULL,               -- 方法版本
  direction      VARCHAR(16)  NOT NULL,               -- 枚举小写码：bullish/bearish/neutral/conditional，见 DictEnum#judgmentDirection
  horizon_days   INT          NOT NULL,               -- 验证窗口（交易日）
  expire_at      DATE         NOT NULL,               -- 到期日 yyyy-MM-dd（由 trade_cal 按交易日推算；E6 存量例外）
  confidence     VARCHAR(4)   NOT NULL,               -- 置信度中文单字：高/中/低
  payload        TEXT         NOT NULL,               -- JSON 文本（完整判断 Schema 2.0）
  frozen         TINYINT      NOT NULL DEFAULT 1,     -- 0/1 存档即冻结
  superseded     TINYINT      NOT NULL DEFAULT 0,     -- 0/1 被新版本替代
  active_flag    TINYINT GENERATED ALWAYS AS (CASE WHEN superseded = 0 THEN 1 ELSE NULL END) STORED,
  invalidated_at DATETIME     NULL,                   -- 唯一允许更新的时间列（Asia/Shanghai）
  created_at     DATETIME     NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_judgment ON judgment (judgment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_judgment_active ON judgment (ts_code, trade_date, pipeline_id, active_flag);
CREATE INDEX IF NOT EXISTS idx_judgment_active ON judgment (trade_date, ts_code, pipeline_id, superseded);
CREATE INDEX IF NOT EXISTS idx_judgment_expire ON judgment (expire_at, frozen);
```

要点：

1. `id` 永远第一列，审计列永远最后两列（`created_at`、`updated_at`），中间是业务列；
2. 每列必须有对齐的类型列，**类型不许写成"看着像"的东西**（`string`、`number`、`varchar` 不带长度）；
3. 每个枚举/日期/金额/数量/引用列都要有行内注释（值域或单位或目标）；
4. 唯一键 = 业务幂等键；**能防重的表不许只有普通索引**；
5. 局部唯一（"同组只有一行有效"）用「生成列 + NULL 逃逸」，不用应用层判断（`uk_judgment_active` 是标准写法）；
6. **事实/证据表按运行隔离**：由某次运行产出、且被其它不可变资产（判断、报告）引用的事实，幂等键必须包含
   `run_id` 并**只追加不覆盖**（`unit_fact` 是标准写法）。"事实可重算所以可以覆盖"与"判断冻结不可改"是矛盾的
   ——覆盖之后旧判断的溯源就断了。

---

## 5. 类型矩阵（唯一允许的类型集合）

| 语义 | 声明类型 | 说明 |
|---|---|---|
| 代理主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | 自增，全局唯一 |
| 引用 id | `BIGINT` | 引用其它表 `id`；不建外键 |
| 业务编号 | `VARCHAR(40)` | `J-2026…`、`run_id` 类；不要用 12/20 这种刚好卡死的长度 |
| 代码/编码 | `VARCHAR(12)` | `ts_code`（镜像表沿用）、`tag_code`(40)、`config_key`(64) 按实际定 |
| 枚举 | `VARCHAR(16)` | 唯一允许的枚举宽度；码长超过 15 字符用 `VARCHAR(32)`（如 `BLOCK_DELETE_INSERT`） |
| 名称 | `VARCHAR(128)` | 股票名、单位名 |
| 短文本 | `VARCHAR(255)` | 备注、标题 |
| 长文本 | `TEXT` | JSON 载荷、报告正文、错误堆栈摘要 |
| 业务日期 | `CHAR(8)` | `yyyyMMdd`，如 `20261124` |
| 年月 | `CHAR(6)` | `yyyyMM`，如 `202611`（仅在新表确有月度分区键时使用） |
| 系统时间 | `DATETIME` | `yyyy-MM-dd HH:mm:ss` 文本，本地时区（§6） |
| 金额 | `DECIMAL(24,4)` | 元；4 位小数 |
| 数量/股数/手数 | `DECIMAL(24,4)` | 与金额同精度，单位写注释 |
| 价格/比率/百分比 | `DECIMAL(20,4)` | 价格（元）、比率（0.1234）、百分比（12.34） |
| 计数 | `INTEGER` | 户数、条数、重试次数；**不要用 DECIMAL** |
| 布尔 | `TINYINT` | `0/1`，`NOT NULL DEFAULT 0` |
| 毫秒耗时 | `BIGINT` | `duration_ms` |

**禁止**：`FLOAT`/`REAL`/`DOUBLE`（金额精度）、`BOOLEAN`（SQLite 无真布尔）、`JSON`（无此类型，用 `TEXT`）、不带长度的 `VARCHAR`、`NUMERIC` 泛型、同一语义两种精度混用。

### 5.1 精度纪律（SQLite 的现实）

SQLite 的 `DECIMAL(p,s)` 只是**文档**：NUMERIC 亲和性下，写入 `12.34567` 会原样存成 REAL。所以：

- 精度由**应用侧**保证：金额/数量/比率读出后统一 `BigDecimal.setScale(4, RoundingMode.HALF_UP)`（工具：`common.util.Decimals`）；
- 禁止在 SQL 里做**累加后的等值比较**（浮点误差）；需要精确汇总时在 Java 用 `BigDecimal`；
- 新增"钱"的表若要求分毫不差（如成本记账），用「整数最小单位 + 币种列」（`cost_cents BIGINT` + `currency VARCHAR(8)`），不要用 DECIMAL——
  唯一例外是存量 `llm_usage.cost DECIMAL(12,6)`（美元单价级金额，需要 6 位小数），已登记附录 E12，T17 成本控制时改造。

---

## 6. 时间口径（全库唯一两种）

| 用途 | 类型 | 格式 | 谁写入 |
|---|---|---|---|
| 系统时间（创建/更新/起止/失效） | `DATETIME` | `yyyy-MM-dd HH:mm:ss` | 应用显式写（`AuditMetaObjectHandler`）或 `DEFAULT (datetime('now','localtime'))` |
| 业务日期（交易日/报告期/公告日） | `CHAR(8)` | `yyyyMMdd` | 应用按业务规则写 |

规则：

1. **时区固定 Asia/Shanghai**（`Clock` 统一注入，禁止 `LocalDateTime.now()` / `new Date()`），DEFAULT 用 `datetime('now','localtime')` 与之对齐；
2. **不用 `DATE` 类型**：`CHAR(8)` 与 `DATE` 在 SQLite 亲和性不同，无法直接比较排序（存量 `judgment.expire_at` 已登记豁免，新表禁用）；
3. **不用 `create_time`/`check_time`/`start_time` 等平行命名**（统一 `_at`）；业务时间点（如质量检查时刻）若本质是业务事件，用 `check_at` 并在注释说明；
4. **不用 `ON UPDATE CURRENT_TIMESTAMP`**（SQLite 不支持），更新时间由应用写；`updated_at` 缺失的表视为不可变/追加型表；
5. **不用毫秒时间戳整数列**（`created_ms BIGINT`）；需要毫秒精度用 `DATETIME` + 应用侧格式化，或 `*_ms` 表示**耗时**而非时刻。

---

## 7. 审计列（业务表强制）

| 表性质 | 必需列 |
|---|---|
| 可变业务表（会被 UPDATE） | `created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))` + `updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))` |
| 追加型/日志型（只 INSERT） | `created_at` 必需；`updated_at` 可省 |
| 纯关联/引用表（只插入删除） | `created_at` 足够 |
| 镜像表 | 不需要 |

- 新实体一律 `extends BaseEntity`（拿到 `createdAt`/`updatedAt` 与 `AuditMetaObjectHandler` 自动填充）；
- `sys_config` 是"只有 `updated_at`"的存量例外，**新增表不得效仿**；
- **多用户（2026-11-24 起）**：不再用 `created_by`/`updated_by` 给所有表加操作人；改为「谁的数据」用 `user_id` 列表达（`user_preference` / `user_watchlist`），唯一键包含 `user_id`，服务层以当前登录用户过滤。需要审计"谁改的"时再加 `*_by`，不要提前给全库加列。

---

## 8. 索引与唯一键

### 8.1 命名（L2 校验）

```
uq / uk :  uk_<表名>                    -- 自然键（幂等键）
           uk_<表名>_<用途>              -- 第二个唯一约束（如 uk_judgment_active）
idx     :  idx_<表名>_<用途或列>         -- 普通索引，用途取 del/poll/status/expire/date 等短词
```

- 索引名在 SQLite 是**全库唯一命名空间**，因此必须带表名，防止撞名；
- 禁止 `uk_active`、`idx_expire`、`idx_direction` 这类"看不出表"的名字（2026-11-24 已全部更名）；
- 名称长度 ≤ 40 字符（兼容导出工具；`idx_method_version_changelog_version` 为当前最长 38 字符）。

### 8.2 策略

| 规则 | 说明 |
|---|---|
| 每张业务表索引预算 ≤ 3（不含主键） | 新增索引必须在注释里写出**对应查询** |
| 唯一性优先用 `uk_`，而不是应用层去重 | 并发下应用去重不可靠；表级 `PRIMARY KEY (...)` 也算唯一约束（如 `logic_tag_dict`） |
| 键序 = 查询的最左前缀 | 本项目两类主查询：`(ts_code, trade_date)` 按股票取历史；`(trade_date, ts_code)` 按日截面。表一旦选定键序，同类表保持一致 |
| 最左前缀可覆盖的索引不要重复建 | `uk(a,b)` 已覆盖按 `a` 的查询，不要再建 `idx(a)` |
| 长文本不进索引键 | `VARCHAR(255)` 以上列不进 `uk`/`idx` 的键（体积与写放大） |
| 删除键索引：整块删插表必须有 | 删除键 = 唯一键列集合；否则"先删后插"会全表扫 |
| 不建"将来可能用到"的索引 | 先用后建，建时必须附查询 |

### 8.3 生成列 + 局部唯一（标准写法）

"同一 (a,b) 只有一行有效"这类约束用生成列实现，不靠应用层：

```sql
superseded  TINYINT NOT NULL DEFAULT 0,
active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN superseded = 0 THEN 1 ELSE NULL END) STORED,
CREATE UNIQUE INDEX uk_x_active ON x (a, b, active_flag);   -- NULL 不参与唯一
```

### 8.4 外键：不建

SQLite 的外键需要每连接 `PRAGMA foreign_keys=ON`，且本项目采用"先删后插/批量 upsert"的写入模式，外键会引入顺序耦合与批量失败回滚面。**决策：不建数据库外键，引用完整性由应用层校验 + 单测保证**（`report_id`、`run_id`、`judgment_id` 等）。新增引用关系时，必须在服务层显式校验"目标存在"，并补一条负向用例。

---

## 9. 枚举列与字典

1. 枚举列统一 `VARCHAR(16) NOT NULL`（长码 `VARCHAR(32)`），存**大写英文码**（`SUCCESS`、`INCR`、`BULLISH`）；
2. 必须给 `DEFAULT`（除非业务上"必须由调用方明确指定"，如 `ingest_log.status`）；
3. 每个枚举域必须在 `common.enums` 或领域包内实现 `DictEnum`（`code/label/color/sort`），前端下拉/标签由 `GET /api/common/dicts` 下发——**不许前端硬编码中文映射**；
4. 枚举新增值 = 兼容变更（只增不改码），删除/改码 = 破坏性变更，需要数据迁移；
5. 布尔不要用 Y/N 存库：上游 Y/N 在接入层转 `0/1`（`YesNoEnum` 负责边界转换）。

---

## 10. 注释

SQLite 不持久化 `COMMENT`，注释只存在于 `db/schema.sql`；因此**注释就是文档**，规范要求：

1. **每张表必须有表级注释**，新格式：

```sql
-- judgment：判断存档 ｜ 唯一键=judgment_id；有效行=(ts_code,trade_date,pipeline_id) ｜ 无金额列
```

   存量 `-- [原 MySQL 表注释] …` 在**下一次修改该表时**顺手改成新格式（已切 SQLite，旧标记名不副实）。

2. **以下列必须有行内注释**：金额、数量、比率、日期、枚举、引用 id、JSON 载荷、生成列；
3. 注释写"值域/单位/目标"，不写"这是字段名"这种废话：

```sql
amount DECIMAL(24,4) NULL,   -- 成交额，单位：千元（不换算）
status VARCHAR(16) NOT NULL, -- 枚举：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED
```

---

## 11. SQL 书写规范

| 规则 | 说明 |
|---|---|
| **禁止 `SELECT *`** | 显式列名；镜像表动态 SQL 也必须由注册表 `fields` 生成列串 |
| 禁止在 Java 里拼 SQL 字符串 | 业务表用 MyBatis-Plus Wrapper / 类型化 Mapper；镜像表用 Provider；DDL 用 `ScriptRunner` |
| 参数一律 `#{}` 绑定 | 禁止 `${}` 拼值（唯一例外：表名/列名白名单，且必须过正则白名单） |
| 列表查询必须分页 | `LIMIT/OFFSET`；页面一次最多 100 行（`PageQuery.MAX_SIZE`） |
| 排序必须确定 | `ORDER BY` 带唯一列兜底（`... , id`），否则分页会漂移 |
| 别名必须写 | 多表/聚合查询 `AS` 清晰命名，禁止 `t1.t2` 无意义别名 |
| 聚合查询必须有业务时间范围 | `COUNT(*)` 全表在 41 张表上是事故（`daily` 可达千万行） |
| 批量写 500 行/批 | 走 `MyBatisBatchExecutor`；HTTP 调用不包事务 |
| 事务只包写 | 读长事务会阻塞 SQLite 写；`@Transactional` 只在写服务方法上 |
| 禁止在 SQL 里做格式化/时区转换 | 时间格式化在 Java（`DateTimes`） |
| DDL 只能出现在 `db/schema.sql` / `db/migration/` | 业务代码里禁止 `CREATE`/`ALTER`/`DROP` |

### 11.1 镜像表动态 SQL 的边界

41 张镜像表**不写 41 份 Mapper/XML**，由 `TushareSqlProvider` 按 `TableDef`（表名+列串+幂等键）生成 `INSERT … ON CONFLICT(键) DO UPDATE SET 列=excluded.列`。规则：

- 表名/列名只能来自 `TushareTableRegistry`，并对标识符做正则白名单校验；
- 值全部 `#{}`；
- 冲突目标必须等于注册表 `keys`，由 `UpsertConflictTargetTest` 守护；
- 新增/修改镜像表字段 = 同时改 `db/schema.sql` + `TushareTableRegistry` + 单测（`RegistrySchemaConsistencyTest` 会逐列比对）。

---

## 12. DDL 变更流程（两种节奏，别混）

### 12.1 1.0 之前（当前阶段）：改脚本 + 删库重建

```bash
# 1) 改 db/schema.sql
# 2) 重建（开发期，数据可丢）
rm -f data/refine_loop.db data/refine_loop.db-wal data/refine_loop.db-shm
# 3) 跑门禁（会在临时库上全量建表）
mvn -B test
```

**必须知道的坑**：schema 全部是 `CREATE TABLE IF NOT EXISTS`，对**已存在的库不会修改表结构**。所以"改了 schema 但没删库"会导致运行库缺列/缺索引，且只在运行时炸。这就是 1.0 前必须删库重建的原因。

### 12.2 1.0 之后（有真实数据）：版本化迁移

1. 新建 `db/migration/V<4 位序号>__<小写下划线描述>.sql`，例如 `V0002__add_judgment_state.sql`；
2. 只写**增量**（`ALTER TABLE` / `CREATE INDEX` / 数据回填），不修改 `V0001` 及历史脚本；
3. 迁移脚本必须幂等或由版本表保证只执行一次（`schema_version(version TEXT PRIMARY KEY, applied_at DATETIME)`）；
4. 执行顺序 = 版本号升序；失败即中止启动（fail-fast），不允许"半迁移"状态；
5. 破坏性变更（删列/改类型/改码）必须：备份库文件 → 迁移脚本 → 应用兼容读 → 单独发布；
6. 任何迁移 PR 必须附：影响行数估算、回滚方案、备份命令。

> 迁移器落地前，1.0 前一律走 12.1。**不允许** 用"手工改运行库"的方式绕过。

### 12.3 备份（上生产前）

```bash
# SQLite 一致性快照（不能直接 cp 带 WAL 的库）
sqlite3 data/refine_loop.db "VACUUM INTO '/data/refine-loop/backup/refine_loop-$(date +%Y%m%d).db'"
# 保留 14 天，每日 02:00 定时（架构 §8.3）
```

---

## 13. 新增表的自查清单

- [ ] 表名单数、snake_case、领域前缀正确；
- [ ] `id` + 业务列 + 审计列，列序符合模板；
- [ ] 类型全部来自 §5 矩阵，金额/数量/比率/日期/枚举列有行内注释；
- [ ] 有业务幂等键，且用 `uk_<table>`（或生成列局部唯一）落实；
- [ ] 索引 ≤3 个且每个都写得出对应查询；
- [ ] 表级注释写清中文名、唯一键、单位口径；
- [ ] 同步实体（`extends BaseEntity`）、Mapper、单测、注册表（若镜像表）；
- [ ] 跑过 `mvn -B test`（`SchemaConventionTest` 会自动校验本规范）。
