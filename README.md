<h1 align="center">RefineLoop</h1>

<p align="center">
  <b>盘后自动选股分析 → 结构化判断存档 → 走势验证与偏差归因</b><br>
  会复盘、能证伪、越用越准的个人投研工具
</p>

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white">
  <img alt="MyBatis-Plus" src="https://img.shields.io/badge/MyBatis--Plus-3.5-2C2D72">
  <img alt="SQLite" src="https://img.shields.io/badge/SQLite-3-003B57?logo=sqlite&logoColor=white">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white">
</p>

---

## 这个项目解决什么问题

市面上的「AI 炒股」大多止步于一次问答：问完就散，结论存不下来；判断对错只凭感觉，说不清哪里错了；用了半年，工具也不会变得更好用。

RefineLoop 用一条每天盘后自动运行的流水线，把它们闭合成一个循环：

```
每日盘后
   │
   ├─ 标的初筛 ──> 按基本面 / 技术面 / 流动性规则，从全市场收敛出候选池
   ├─ 深度分析 ──> 多维度原子能力产出分析报告 + 结构化判断
   │                （方向、有效期、关键价位、逻辑标签、方法版本）
   ├─ 判断存档 ──> 不可篡改、只可作废，任何结论都能回溯到当时的数据与方法版本
   ├─ 走势验证 ──> 到期判断自动与真实走势、基准指数比对
   └─ 偏差归因 ──> 定量拆解 → 外因排查 → 内因定位，指出哪个分析维度失准
                       │
                       └──> 人工调整方法与规则，进入下一轮初筛
```

关键差别不在于「会不会调用大模型」，而在于**判断是否留痕、能否被证伪、误差是否被归因**。

## 它是什么，不是什么

| 是 | 不是 |
|---|---|
| 盘后自动运行的投研流水线 | 量化交易系统：不自动下单、不做高频交易 |
| 结构化、可回溯的判断资产库 | 盘中实时行情软件：只做日级数据 |
| 标准化的偏差归因与效果统计 | 对外荐股产品或商业化服务 |
| 原子能力 + 流水线编排的可扩展平台 | 会自动修改自身规则的黑盒 |

所有买卖决策由人完成，系统只提供数据、分析与归因。

## 核心设计

**1. 原子能力 + 流水线编排**

分析动作拆成可复用的最小能力单元（估值分位、财务质量、趋势判断、事件归因……），每个单元有独立的方法、数据依赖与输出契约；上层用固定流程把单元编排成流水线。新增场景只写编排，不重写分析逻辑。

**2. 结构化判断存档**

报告必须同时产出机器可读的判断块：方向、有效期、关键价位、逻辑标签、方法版本。存档后只能作废、不能改写——验证与统计才有真实样本。

**3. 全链路版本管理**

筛选规则、分析方法、能力单元、流水线流程全部版本化；每份报告与判断绑定当时的版本号，可以按版本对比迭代前后的判断质量。

**4. 标准化偏差归因**

先定量拆解（市场 / 行业 / 个股的收益贡献），再排查外因（公告、事件、资金），最后定位内因（哪个分析维度的方法有缺陷）。缺少事件数据时显式标注「外因不可归因」，不允许模型猜测补全。

**5. 数据质量先行**

所有输入分析的数据先过质量门：缺失、过期、异常必须显式标注；关键数据异常时自动降级分析深度并提示，而不是基于坏数据交出看起来完整的结论。

## 当前进度

| 模块 | 状态 | 内容 |
|---|---|---|
| 数据地基（T1–T5） | ✅ 完成 | 61 表 schema、Tushare 客户端与限流、五种更新策略的接入编排、数据质量门 |
| 数据中心运维台 | ✅ 完成 | 数据总览、接入作业、质量门、初始回填向导（Vue 3） |
| 多用户与权限 | ✅ 完成 | 固定角色 + 权限码、登录会话、交易偏好、自选股 |
| 能力单元框架（T6–T11） | 🚧 开发中 | SPI 与运行时、LLM 调用基座、L3 校验链、判断存档 |
| 流水线引擎与 P1 单元（T12–T18） | ⏳ 规划 | DSL 与 Runner、compute / interpret 单元、成本控制、报告与调度 |
| 前端页面与手动单股分析（T19–T23） | ⏳ 规划 | 页面骨架、单股查证、部署验收 |

> 目前可用的部分是「数据地基 + 运维台」：能自动接入 41 张 Tushare 数据表、按限流配额跑作业、执行质量门检查；分析流水线尚未落代码。

## 系统架构

```
浏览器（Vue 3 数据中心运维台）
   │   HTTP / JSON，统一 Result<T>
   ▼
Spring Boot 3.5 单体（Java 21，虚拟线程）
   ├─ web       REST 适配：解析入参、调用服务、包装 Result
   ├─ 领域       ops（运维读模型）/ tushare（数据接入）/ user（账号权限）
   ├─ data      唯一 SQL 出口：MyBatis-Plus 实体与 Mapper
   ├─ common    基础设施：Result、错误码、字典、缓存、日志、分页
   └─ config    装配根，可依赖所有层

   ├─ MyBatis-Plus ──> SQLite 单文件库（WAL，免运维）
   └─ HTTPS ─────────> Tushare Pro 日级数据 / DashScope 大模型（后续阶段）
```

- `web` 只解析入参、调用服务、包装 `Result`，不写业务逻辑、不碰数据访问层；
- `data` 是唯一 SQL 出口，生产代码零 `JdbcTemplate`；
- 依赖方向单向：`web → 领域 → data → common`，由 ArchUnit 测试固化，改错方向会直接红。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus 3.5（实体 + `BaseMapper` + 动态 SQL Provider + BATCH 执行器） |
| 数据 | SQLite 3 单文件库（WAL 模式，随应用启动自动建库建表，无需独立数据库服务） |
| 基础设施 | Caffeine + Spring Cache · Guava（限流平滑）· fastjson2 · Hibernate Validator |
| 接口与文档 | REST + 统一 `Result<T>` · springdoc-openapi（Swagger UI） |
| 前端 | Vue 3 · Vite · TypeScript · Pinia · Vue Router（不引入重型 UI 框架，样式为自写 CSS） |
| 测试 | JUnit 5 + ArchUnit · 真实 SQLite 临时库 · `MockRestServiceServer`（全程不联网） |

## 快速开始

**前置要求**：JDK 21+、Maven 3.9+；前端需要 Node.js 22+。不需要安装数据库，也不需要 Tushare token 就能把服务跑起来（只是库里没有行情数据）。

### 1. 启动后端

```bash
mvn clean package
java -jar target/refine-loop-0.1.0-SNAPSHOT.jar
```

首次启动会自动创建 `./data/refine_loop.db`、执行 `db/schema.sql` 建齐 61 张表，并创建管理员账号 **admin / admin123**（生产环境务必用 `REFINE_LOOP_BOOTSTRAP_PASSWORD` 覆盖，并在首次登录后立即改密）。

验证：

```bash
curl http://127.0.0.1:8080/api/health
# {"code":0,"msg":"ok","data":{"status":"UP","app":"refine-loop","time":"..."}}
```

接口文档：<http://127.0.0.1:8080/swagger-ui.html>

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev        # 默认 http://127.0.0.1:5173，/api 已代理到 8080
```

登录后可进入：**数据总览**（41 张表的健康度、数据预览、单表增量 / 回填、每日开关）、**接入作业**（编排进度、作业历史与失败重试、质量门、初始回填向导）、**我的账户**（交易偏好、自选股）、**用户管理**（仅管理员）。

### 3. 生产部署

```bash
export TUSHARE_TOKEN=...        # 数据接入凭据
export DASHSCOPE_API_KEY=...    # 大模型阶段使用
java -Xmx1g -jar refine-loop.jar --spring.profiles.active=prod
```

生产 profile 默认库文件为 `/data/refine-loop/db/refine_loop.db`、日志目录 `/data/refine-loop/logs`，均可用环境变量覆盖。前端 `npm run build` 产出纯静态 `dist/`，用 Nginx 托管并反向代理 `/api` 即可。

## 配置

敏感项一律走环境变量，仓库中不含任何明文密钥。

| 变量 | 必填 | 说明 |
|---|---|---|
| `TUSHARE_TOKEN` | 接入数据时必填 | Tushare Pro token |
| `DB_URL` | 否 | SQLite 数据源 URL；开发默认 `jdbc:sqlite:./data/refine_loop.db`（WAL、`busy_timeout=5000`） |
| `TUSHARE_BASE_URL` | 否 | 默认 `http://api.tushare.pro` |
| `DASHSCOPE_API_KEY` | 后续阶段 | 大模型调用密钥 |
| `REFINE_LOOP_BOOTSTRAP_USERNAME` / `REFINE_LOOP_BOOTSTRAP_PASSWORD` | 否 | 首次启动创建的管理员账号，默认 `admin` / `admin123` |
| `REFINE_LOOP_BOOTSTRAP_ENABLED` | 否 | 默认 `true`；关闭后首次启动不自动建号 |
| `REFINE_LOOP_TOKEN_TTL_DAYS` | 否 | 登录令牌有效期（天），默认 `7` |
| `REFINE_LOOP_PASSWORD_MIN_LENGTH` | 否 | 密码最小长度，默认 `8` |

## 数据接入

- **数据源**：Tushare Pro 日级数据，40 个接口映射到 **41 张镜像表**；字段、幂等键、单位口径登记在 `TushareTableRegistry`，与 `db/schema.sql` 逐列一致（有单测守护）。
- **限流**：`src/main/resources/ratelimit.yml` 按 `api_name` 独立配置每分钟配额（官方值留 10%~25% 余量，如 `daily` 400、`adj_factor` 160、`share_float` 48），自然分钟窗口内阻塞等待，避免触发上游限流报错。
- **更新策略**：全量回填 / 增量 / 区间 / 三态补数 / 特殊分页五种；每日编排按「维表层 → 行情层 → 财务与事件层」分层执行，单实例互斥。
- **可观测**：每次作业写 `ingest_log`（表、类型、状态、行数、耗时、错误摘要），配额消耗写 `rate_limit_counter`，质量检查写 `quality_check_log`。

命令行入口（三组参数互斥，不带参数启动则不执行任何接入）：

```bash
# 每日增量编排（同步执行，逐层逐表）
java -jar refine-loop.jar --ingest.daily

# 指定表全量回填（逗号分隔）
java -jar refine-loop.jar --ingest.full=stock_basic,trade_cal,index_basic

# 单表增量 / 区间全量
java -jar refine-loop.jar --ingest.table=daily --ingest.mode=incr
java -jar refine-loop.jar --ingest.table=daily --ingest.mode=full --ingest.start=20150101 --ingest.end=20261124
```

同样的操作也可以在运维台上完成：单表「增量 / 回填」、每日开关、失败重试、初始回填向导。

## API 概览

所有接口返回 `Result<T>`：`{"code":0,"msg":"ok","data":...}`，HTTP 恒定 200，业务语义只由 `code` 表达。除白名单（`/api/health`、`/api/auth/login`、Swagger 文档）外均需登录，写操作按权限码校验。

| 前缀 | 说明 |
|---|---|
| `GET /api/health` | 健康检查（公开） |
| `/api/auth` | 登录、登出、当前用户、修改密码 |
| `/api/users`、`/api/users/me/preferences`、`/api/users/me/watchlist` | 用户管理（管理员）、交易偏好、自选股 |
| `/api/common` | 字典枚举下发、缓存管理 |
| `/api/ops/tables` | 表清单、表详情、数据预览、单表作业、增量 / 回填、每日开关 |
| `/api/ops/ingest` | 每日编排、运行中编排进度、作业历史与重试、回填计划 |
| `/api/ops/quality` | 质量门最新结果、手动执行、历史记录 |
| `/api/ops/stats` | 今日数据概览统计 |

完整端点清单见 [接口契约规范 §9](.claude/rules/04-接口契约规范.md)。

## 项目结构

```
refine-loop/
├── src/main/java/com/arthur/refineloop/
│   ├── common/    基础设施：Result、错误码、字典、缓存、日志、分页、工具类
│   ├── config/    Spring 装配根（数据源、鉴权拦截、线程池、缓存）
│   ├── data/      SQL 唯一出口：entity / mapper / provider / batch / handler
│   ├── ops/       运维读模型：表总览、接入作业、质量门、统计
│   ├── tushare/   Tushare 客户端、限流、接入编排（model / dto / registry / service）、质量门
│   ├── user/      账号域：角色与权限、认证、偏好、自选股
│   └── web/       REST 适配层：Controller + 入参 record
├── frontend/      Vue 3 数据中心运维台
├── db/schema.sql  全量建表脚本（61 表 / 74 索引，表结构唯一事实源）
├── docs/          设计文档与实施记录
├── CLAUDE.md      AI 协作入口：红线、常用命令、规范加载地图
└── .claude/       工程规范（rules）与流程技能（skills）
```

## 测试

```bash
mvn -B test                    # 后端全量：真实 SQLite 临时库，不联网、无需 token
cd frontend && npm run build   # 前端类型检查 + 生产构建
```

部分工程约束直接写成了测试，防止规范随代码漂移：

- `ArchitectureRulesTest`：包依赖矩阵、`web` 不直连 mapper / entity、Controller 必须返回 `Result`、禁用 `JdbcTemplate` / `System.out` 等；
- `SchemaConventionTest`：表 / 列 / 索引命名、类型与精度矩阵、MySQL 方言残留；
- `KnowledgeBaseFilesTest`：文档死链、规范与 AI 入口的唯一性。

## 文档

| 文档 | 内容 |
|---|---|
| [项目整体介绍与设计总纲](docs/项目整体介绍与设计总纲.md) | 定位、核心机制、里程碑、边界 |
| [技术架构与模块设计](docs/技术架构与模块设计.md) | 技术选型、分层与包结构、模块详设、ADR |
| [核心能力体系设计详案](docs/核心能力体系设计详案.md) | 原子能力 Spec、判断 Schema、流水线编排 |
| [Tushare 接口对接手册](docs/Tushare接口对接手册.md) | 40 接口 / 41 表契约、幂等键、限流、单位口径 |
| [前端设计详案](docs/前端设计详案.md) | 页面信息架构与交互 |
| [开发任务计划](docs/开发任务计划.md) | T1–T23 任务拆分与验收标准 |
| [T2–T5 实施报告与偏差记录](docs/T2-T5实施报告与偏差记录.md) | 数据地基阶段的实施事实与决策记录 |
| [数据中心交互原型](docs/原型图/数据中心-交互原型.html) | 运维台的页面结构与视觉稿（HTML 原型，浏览器直接打开） |

工程规范（分层、命名、数据库、接口契约、前端、测试、Git 流程）集中在 [`.claude/rules/`](.claude/rules/00-总纲.md)，AI 协作入口是 [`CLAUDE.md`](CLAUDE.md)。

## 路线图

| 阶段 | 目标 | 状态 |
|---|---|---|
| M1 | 基础能力 + 标的发现与深度筛选流水线，每日产出候选池与分析报告 | 进行中（数据地基已完成） |
| M2 | 持仓跟踪与异动分析流水线，统一报告与判断存档 | 规划 |
| M3 | 判断验证与偏差归因闭环，版本管理与效果统计 | 规划 |
| M4 | 扩展能力与更多流水线，量化迭代前后的效果 | 规划 |

## 已知限制

- 当前环境没有 `TUSHARE_TOKEN`：真实 Tushare 冒烟、全量初始回填（约 3~5 小时）与前端真实数据联调仍待联调环境执行。
- 只处理日级数据，不做盘中行情与分钟级分析。
- 分析流水线（能力单元、判断存档、偏差归因）尚在开发，现阶段可用的是数据接入与运维台。

## 声明

本项目为个人自用的投资研究工具，不对外提供投资建议，也不承诺任何收益。市场有风险，任何决策及其后果由使用者自行承担。
