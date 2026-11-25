# refine-loop · 项目说明与开发约束

> 本文件是**唯一的 AI 入口**（Claude Code 自动加载；原 `AGENTS.md` 已合并进来并删除，不再有第二份）。
> 工程规范正文在 `.claude/rules/`，按 `paths` 自动注入（见 §8）；本文件只放「每次会话都必须知道」的部分，
> 冲突时以 `.claude/rules/` 与门禁测试为准。

## 1. 项目是什么

个人股票分析与验证闭环系统（单服务器、单体、前后端分离）：每日盘后完成「标的初筛 → 深度分析 → 结构化判断存档」，
并对历史判断做走势验证与偏差归因。

- 后端：Java 21 · Spring Boot 3.5 · MyBatis-Plus · SQLite 单文件库（`db/schema.sql` 是表结构唯一事实源，启动期自动建表）
- 前端：Vue 3 + Vite + TS（`frontend/`，数据中心运维台）
- 分层：`web → 领域(ops/tushare/user) → data → common`；`capability` 依赖 `data/common/config`；`config` 是装配根
- 阶段：Phase A 地基（Tushare 接入 + 运维台 + 账号域）已完成；T6 能力单元框架、T7 LLM 双协议基座已落地；`pipeline/judgment/report/version` 尚未落代码

## 2. 十条红线（违反即返工）

1. **Controller 不写业务**：不写库、不拼业务文案、不翻译状态；只解析参数 → 调服务 → `Result.ok(...)`。
2. **web 不碰 `data.mapper` / `data.entity`**；`common` 不依赖任何业务域（`ArchitectureRulesTest` 会拦）。
3. **生产代码禁止 `JdbcTemplate`、禁止裸 SQL 字符串**；SQL 只在 `data` 层。
4. **Controller 不返回 `*Entity`**；出参用 record（`*View`），入参用 `*Request`。
5. **表结构唯一事实源是 `db/schema.sql`**；改表必须同一次提交更新实体 + 注册表 + 单测 + 注释。
6. **金额/数量/比率必须有单位口径**（列注释或单位后缀）；金额 `DECIMAL(24,4)`、价格/比率 `DECIMAL(20,4)`，不许混用。
7. **时间只有两种**：系统时间 `LocalDateTime`（`DATETIME`）、业务日期 `yyyyMMdd` 的 `CHAR(8)`；禁用 `Date`/`Instant`；`LocalDateTime.now()` 禁用（注入 `Clock`）。
8. **接口一律返回 `Result<T>`**，`code=0` 成功；HTTP 恒 200，业务语义只由 `code` 表达。
9. **不吞异常**：`catch` 必须记日志或转 `ApiException`；禁空 `catch`、`printStackTrace`、`System.out`。
10. **新增依赖必须登记**（`pom.xml` 版本 + 《技术架构与模块设计》§1）；前端运行时依赖上限是 vue/pinia/vue-router。

## 3. 目录与分层（改代码前先定位）

```
com.arthur.refineloop
├── common/     基础设施（Result/ApiException/page/error/constant/enums/dict/cache/log/util/validation）
│                禁令：不依赖业务域、不放 @RestController
├── config/     Spring 装配（装配根，可依赖所有层）
├── data/       唯一 SQL 出入口（entity|mapper|provider|batch|handler）
├── ops/        运维读模型域（TableOpsService/StatsService/QualityOpsService/IngestOpsService/OpsParams/OpsViews）
├── tushare/    client|ratelimit|quality + ingest/{model,dto,registry,service}
├── user/       账号域（model 角色/权限 + service 账号/认证/偏好/自选股 + UserViews）
├── web/        GlobalExceptionHandler|HealthController + common/ + ops/ + user/ + auth/（Controller + *Requests）
├── capability/ 能力单元框架：spi/（契约+FactBundle）| runtime/（UnitRegistry/FactStore/UnitVersionStore）| runtime/llm/（LlmInvoker/双协议 ChatClient/ReportGenerator）| units/（T9 起）
└── (规划) pipeline/ judgment/ report/ version（无代码不建空目录/不塞 .gitkeep）
```

依赖方向：`web → 领域(ops/tushare/user) → data → common`；`capability` 只依赖 `common/config(属性)/data`，禁止依赖 `web/ops/tushare/user/pipeline/judgment/report/version`；`units → runtime → spi` 单向，单元之间禁止互调；`common` 不依赖任何人；`web` 不依赖 `data`；`user` 不依赖 `ops`/`tushare`。
测试包与主代码同构；夹具放 `support/`；集成测试放 `it/`。

## 4. 命名（新增类必须先在后缀表里找到位置）

`*Controller`（web）· `*Service`（领域用例）· `*Mapper` · `*Entity`（data）· `*View`（出参 record）· `*Request`（入参 record）·
`*Params` · `*Properties` · `*Registry`（元数据）· `*Writer`（唯一落库口）· `*Store` · `*Gate`（校验门）· `*Exception` · `*ErrorCode` · `*Enum`。

禁止：`*DTO`/`*VO`/`*Helper`/`*Manager`/`*Impl`（新代码）、`*Dtos` 混装请求与响应、`Mybatis` 拼写（统一 `MyBatis`）。
方法动词：`get`（无则 404）/`find`（Optional）/`list`/`page`/`count`/`exists`/`create`/`update`·`markXxx`/`delete`/
`run`·`trigger`·`retry`·`toggle`·`refresh`/`require`/`normalize`。

## 5. 写代码的硬性细节

- 构造器注入、字段 `final`；类 ≤400 行、方法 ≤50 行、参数 ≤4（超了用参数 record）；禁 `boolean` 位置参数、禁 `Optional` 作参数。
- 值对象/出参一律 `record`；实体新代码 `@Getter @Setter`（禁 `@Data`）；不继承业务类（只允许 `BaseEntity`）。
- 校验分层：Controller（格式/范围 `@Valid`/`@EnumCode`/`OpsParams`）→ Service（业务规则 `Asserts`）→ DB（唯一/非空）。
- 日志：error 只给真异常（带堆栈）、warn 给可预期失败、info 给里程碑、debug 给细节；不打印大对象/密钥。
- `@Transactional` 只在 service 写方法；事务内禁 HTTP / 文件 IO / LLM；批量走 `MyBatisBatchExecutor`（500/批）。
- 注释：类级 Javadoc 写「职责 / 依赖方向 / 不变量」；注释解释为什么，不同步的注释视为缺陷。

## 6. 数据库与接口

- 业务表模板：`id INTEGER PRIMARY KEY AUTOINCREMENT` → 业务列 → `created_at` → `updated_at` → 末尾 `uk_<table>`；
  索引 `uk_<table>[_<用途>]` / `idx_<table>[_<用途>]`，每表 ≤3 并注明对应查询。
- 枚举列 `VARCHAR(16) NOT NULL` + 默认值（大写码 + 登记 `DictEnum`）；布尔 `TINYINT` 0/1；不建外键；
  不建 `DATE`/`FLOAT`/无长度 `VARCHAR`；列名统一 `*_at`，不用 `create_time`/`start_time`。
- 改完 schema：1.0 前删库重建（`data/refine_loop.db*`）；1.0 后写 `db/migration/V*.sql`（见 02 §12）。
- 接口路径：`/api/ops/{tables,ingest,quality,stats}`、`/api/{域}/{资源}`；多词 kebab；非 CRUD 用白名单动词。
- 分页统一 `PageQuery` + `PageResult`（page≥1、size 1~100，非法直接 400），不手搓 `LIMIT/OFFSET`。
- 错误码：沟通层用 `CommonErrorCode`；业务码按段登记（2001+ 接入、3001+ 运维、4001+ 能力…），只增不改。
- 改响应结构 → **同一提交**改 `frontend/src/api/types.ts` + 渲染处 + 04 §9 端点清单。

## 7. 前端（写 Vue/TS 时）

- 组件三分类落目录：`components/base/`、`components/domain/`、`views/<feature>/components/`；命名 PascalCase ≥2 词。
- `<script setup lang="ts">` → 模板 → `<style scoped>`；props 用 `defineProps<Props>()`；禁 `any`/`v-html`/`console`/静态行内 style。
- 数据获取只经 store/composable；唯一 HTTP 出口是 `api/http.ts`；错误走 `ApiError` + `StateBlock`。
- 状态/枚举中文文案不硬编码（走字典或 `utils/status.ts`）；业务数字（表数量/阈值）不写死在页面。
- 门禁：`npm run build`（类型检查 + 构建）必须通过。

## 8. 加载地图（规范正文只在 `.claude/rules/`，按路径自动注入）

| 你在改 | 自动加载 | 讲什么 |
|---|---|---|
| `src/**`、`frontend/**`、`db/**`、`docs/**` | `.claude/rules/00-总纲.md` | 三级约束、权威顺序、红线、评审清单、规范变更流程 |
| `src/**`、`frontend/**`、`pom.xml` | `.claude/rules/01-工程结构与包结构.md` | 目录/包/依赖矩阵/阈值/新增域与表的标准动作 |
| `db/**`、`data/**`、`src/test/**/schema/**` | `.claude/rules/02-数据库与SQL规范.md` | 表/列/索引/类型/单位口径/DDL 变更流程 |
| `src/main/java/**` | `.claude/rules/03-后端编码规范.md` | 命名总表、类与方法设计、异常/事务/日志/时间、反例库 |
| `web/**`、`frontend/src/api/**` | `.claude/rules/04-接口契约规范.md` | URL/`Result`/错误码分段/分页/鉴权/端点清单 |
| `frontend/**` | `.claude/rules/05-前端编码规范.md` | 目录/组件/状态/样式/类型契约/工具链 |
| `src/test/**` | `.claude/rules/06-测试规范.md` | 分层/命名/夹具/覆盖要求/门禁 |
| `docs/**`、`*.md`、`pom.xml` | `.claude/rules/07-工程流程与Git规范.md` | 分支/提交粒度/发布/备份回滚/文档同步义务 |
| `src/**`、`db/**` | `.claude/rules/附录-存量偏差登记.md` | 已修/待修/豁免台账（改历史代码前先查） |
| `common/**`、`config/**`、`frontend/src/**` | `.claude/rules/公共基础设施使用手册.md` | 响应/错误码/日志/字典/缓存/分页/工具类怎么用 |

一次编辑通常注入 3~5 篇（改 `web/` 会加载 00/01/03/04/附录），这是刻意的：**规范正文只有这一份**，`docs/` 不放副本。
新增或修改条款直接改 `.claude/rules/` 对应文件，并在附录「变更历史」记一行。

## 9. 业务与设计文档（`docs/`，规范不在这里）

| 想知道 | 读 |
|---|---|
| 为什么这样设计（定位、机制、里程碑、ADR） | `docs/项目整体介绍与设计总纲.md`、`docs/技术架构与模块设计.md` |
| 能力单元/流水线/判断 Schema | `docs/核心能力体系设计详案.md` |
| Tushare 契约（40 接口 / 41 表、幂等键、限流、单位） | `docs/Tushare接口对接手册.md` |
| 页面信息架构与交互 | `docs/前端设计详案.md` |
| 任务拆分、实施事实与偏差决策 | `docs/开发任务计划.md`、`docs/T2-T5实施报告与偏差记录.md` |

## 10. 常用命令

```powershell
mvn -B test                        # 后端编译 + 全量测试（真实 SQLite 临时库，不联网、无需 Tushare token）
mvn -B -DskipTests package         # 只打包
java -jar target/refine-loop-*.jar # 启动（首次自动建库建表并创建 admin）
cd frontend; npm run build         # 前端类型检查 + 构建（改了 frontend/ 必跑）
```

开发期改 schema 后删库重建（不做迁移）：删掉 `data/refine_loop.db` 及 `-wal`/`-shm`；1.0 后改用 `db/migration/V*.sql`。

## 11. 本仓库特有的四个坑

1. **镜像表三处同步**：41 张 Tushare 表的字段由上游契约决定，`db/schema.sql`、`tushare/ingest/registry/TushareTableRegistry`、
   `RegistrySchemaConsistencyTest` 必须逐列一致，只改一处会在测试里炸。
2. **`data/` 有两个意思**：仓库根 `data/` 是本地 SQLite 库文件目录（不入库）；`com.arthur.refineloop.data` 是唯一的 SQL 访问层。
3. **参考材料只读**：`docs/开源金融类ai插件参考/`、`docs/旧项目参考-*/` 是不入库的外部参考，不要在里面积累改动。
4. **没有 token 也能开发**：当前环境无 `TUSHARE_TOKEN`，任何"真实抓数"的验证都属于联调环境，不要为了跑通而绕过限流/质量门。

## 12. 多步任务用 skill

- 新增/修改接口 → `.claude/skills/add-api/SKILL.md`
- 新增/修改数据表 → `.claude/skills/add-table/SKILL.md`
- 提交前自检与交付核对 → `.claude/skills/pre-commit/SKILL.md`

## 13. 交付前自检（每次任务结束）

- [ ] `mvn -B test` 绿；改了前端再跑 `cd frontend; npm run build`
- [ ] 新文件已纳入版本控制；无 `target/`、`data/*.db*`、密钥、日志入库
- [ ] 修 bug 带了回归用例
- [ ] 文档同步：改公共能力 → 《公共基础设施使用手册》；改结构 → 01 + `ArchitectureRulesTest`；改表 → `db/schema.sql` 注释；改接口 → 04 §9 + `types.ts`
- [ ] 违反了某条但确定不改 → 登记 `.claude/rules/附录-存量偏差登记.md`（含退出条件）；十条红线无豁免

## 14. 常见任务速查

| 我要… | 去哪里 | 注意 |
|---|---|---|
| 加一个查询接口 | `web` 加 Controller + `*View`；领域加 `*Service` | 禁止在 Controller 注入 mapper |
| 加一张表 | `db/schema.sql` + `data/entity` + `data/mapper` + 单测 | 走 02 §4 模板 |
| 处理 Tushare 新表/字段 | `TushareTableRegistry` + schema + `RegistrySchemaConsistencyTest` | 三处必须同步 |
| 加一个通用能力 | `common/**` + 《公共基础设施使用手册》登记 | 不许引用业务类型 |
| 加一个前端页面 | `views/<feature>/` + `api/<域>.ts` + `stores/<域>.ts` | 类型与后端 `*View` 一致 |
| 加一个需要登录的接口 | 默认即需登录；写动作标 `@RequirePermission(Permission.XXX)` | 公开端点登记 `config/WebMvcConfig.PUBLIC_PATHS` |
| 加一个角色/权限 | `user/model/UserRole` + `Permission` | 权限码只增不改；不引入动态 RBAC 表 |
| 加一个「按用户隔离」的数据 | 表带 `user_id` + 唯一键含 `user_id`；服务层用登录用户 id 过滤 | 禁止信任请求体 userId；补跨用户 404 用例 |
| 不确定放哪个包 | 先读 01 §3/§4 | 拿不准就问，别自创包 |
