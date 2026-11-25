# Tushare 接口对接手册（HTTP 接口契约版）

> 本文档只描述 **Tushare HTTP 接口本身**及其对接约束，不绑定任何编程语言或框架。
> 已按项目实际跑通的 40 个接口逐个核对：请求参数、返回字段、分页上限、频控、批量更新、幂等键、单位口径与已知坑。
> Java / Python / Go 等任何实现都可以直接按本文档对接。
>
> **2026-11-24 按 T2~T5 实测结果修订**：Step 0 用真实 token 对 40 接口逐一实测，共发现 11 处契约偏差（含 `income`/`balancesheet`/`cashflow` 字段串、`express`/`block_trade`/`index_*`/`hk_hold` 列集、`fina_mainbz` 与 `index_weight` 参数、周月线与指数日线单位口径），已回写附录 A/B/C 与第 2.5/3/4/5/6/7/8 章相关条目。与旧版描述冲突处以本版为准。

**覆盖范围**：40 个 Tushare 接口，落 41 张业务表（`stk_weekly_monthly` 按 `freq` 分流到周线、月线两张表）。
非 Tushare 数据源（新闻快讯、公司公告）与本地衍生因子表不在本文范围。

---

## 1. 阅读顺序

1. 第 2 章：HTTP 协议（端点、请求体、响应体、错误码、字段映射）。
2. 第 3 章：分页、限流、批量写入与幂等的通用规则。
3. 第 4 章：全量/增量的五种更新策略。
4. 第 5 章：接口总览表。
5. 第 6 章：逐接口契约卡片。
6. 第 7~9 章：单位口径、已知坑、新接口对接流程。
7. 附录 A：全部接口的 `fields` 字段串；附录 B：限流表；附录 C：表与幂等键。

---

## 2. HTTP 接口契约

### 2.1 端点

| 项 | 值 |
|---|---|
| URL | `http://api.tushare.pro` |
| Method | `POST` |
| Content-Type | `application/json` |
| 鉴权 | 请求体顶层 `token` 字段（Tushare Pro token），不放 Header |

### 2.2 请求体

```json
{
  "api_name": "daily",
  "token": "<your-tushare-token>",
  "params": {
    "trade_date": "20240115",
    "offset": 0,
    "limit": 5000
  },
  "fields": "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `api_name` | 是 | 接口名，如 `daily`、`stock_basic` |
| `token` | 是 | Tushare Pro token，只放配置/环境变量，禁止入库或提交到仓库 |
| `params` | 是 | 业务查询参数 + 分页参数；无参数时传 `{}` |
| `fields` | 是 | 需要的返回字段，逗号分隔；实际返回列是它的**子集**（整列全 NULL 被省略、无效字段名静默丢弃，见 2.5） |

- `offset`、`limit` 建议以字符串传入（Tushare 两种都接受，项目实际用字符串）。
- 不传 `limit` 时单次返回通常上限 5000 行；全市场接口必须显式设置 `limit`。
- 业务参数按接口不同而定，见第 6 章。
- Tushare 参数命名一律下划线：`ts_code`、`trade_date`、`start_date`、`end_date`。

### 2.3 响应体

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "fields": ["ts_code", "trade_date", "close"],
    "items": [
      ["000001.SZ", "20240115", 9.85],
      ["600000.SH", "20240115", 7.21]
    ]
  }
}
```

### 2.4 错误码

| 判定 | 处理 |
|---|---|
| `code == 0` | 成功；`data` 可能为 `null` 或 `items` 为空，按空结果处理 |
| `code != 0` | 失败；读取 `msg` 记录日志并抛出/重试 |

常见错误：

| 现象 | 原因 |
|---|---|
| `code=40203` | 频次超限（接口有每分钟/每天调用上限） |
| `code=50101` | 查询参数组合不合法，Tushare 无法执行查询 |
| 返回字段全空 | `fields` 拼写与官方不一致，或参数名错误 |
| 返回 0 行 | 参数过滤后确实无数据（如周中查周线），不一定是故障 |

### 2.5 字段映射规则

- 返回是**列式**结构：`fields` 是列名数组，`items` 是行数组，按**下标对齐**。
- 解析时先按 `fields[j]` 建列名，再逐行绑定 `items[i][j]`。
- 字段名与值都必须按下标对应，不能假设字段顺序固定。
- Tushare 返回的 `data.fields` 是请求 `fields` 的**子集**：整列全 NULL 的列会被省略、无效字段名会被静默丢弃；必须按响应 `fields` 下标对齐解析，缺失列按 NULL 处理（详见附录 A 前言）。
- 下游字段命名可自行转换（如蛇形转驼峰），但**请求 `fields` 与响应解析必须保持 Tushare 原始下划线名**。

### 2.6 参数与格式约定

| 类型 | 格式/约定 |
|---|---|
| 日期 | `yyyyMMdd` 字符串，如 `20240115` |
| 股票代码 | `000001.SZ`、`600000.SH` |
| 指数代码 | `000300.SH`、`801010.SI` |
| 期货/其他代码 | 按 Tushare 官方后缀 |
| 交易所 | `SSE`、`SZSE`（融资融券）；`SH`、`SZ`（沪深港通） |
| 报告期 `period` | 季度末：`0331`、`0630`、`0930`、`1231` |
| 布尔/标志位 | 多为 `0/1` 字符串或 `Y/N`，按接口字段说明 |
| 数值 | 一律按十进制高精度数值接收，不要提前转 float |

---

## 3. 分页、限流与写入

### 3.1 限流（必须做）

Tushare 对每个接口按**自然分钟**或**自然天**限制调用次数，超限返回 `40203`。

通用做法：

- 按 `api_name` 独立计数，令牌桶/滑动窗口均可。
- 超限时**阻塞等待**到下一窗口，而不是失败重试风暴。
- 配置值要留 10%~25% 余量：Tushare 按自然分钟计数，滑动窗口不会严格对齐。
- 每请求一页都算一次调用（分页会放大调用次数）。
- 单次 `limit` 尽量放大（如 5000），用更少的请求覆盖更多数据。

本项目实测限流值见附录 B。特殊注意：

- `report_rc`：试用档每天 10 次，且隐藏 **1 次/分钟** 限制，翻到第二页会立即 `40203`。
- `fina_indicator`：项目原漏配限流（只靠 30 并发兜底），T3 已按 **150 次/分钟**补配置（见附录 B）。
- `share_float`、`pledge_stat`、`pledge_detail`、`repurchase`：60 次/分钟，是全项目最低档。

### 3.2 分页的三种模式

**A. 单次查询**：适合 per-stock 小结果集、按日全市场（配 `limit=10000`）。
返回行数小于 `limit` 视为已取完。

**B. 标准 offset 分页**：

```
offset = 0
loop:
    rows = request(offset, limit)
    if rows 为空: break
    处理/写入 rows
    if len(rows) < limit: break
    offset += limit
    if 页数 >= MAX_PAGES: 告警并停止
```

通用安全参数：`limit=5000`、`MAX_PAGES=100`。

**C. 自定义分页**：接口单次上限或 offset 上限特殊时使用：

| 接口 | 特殊规则 |
|---|---|
| `stk_limit` | 5000/页，最多 100 页 |
| `stk_weekly_monthly` | 5000/页，最多 100 页 |
| `share_float` | 5000/页，最多 100 页 |
| `suspend_d` | 5000/页；`offset` 达到 100000 时降级为 per-stock |
| `share_float` / `repurchase` / `report_rc` | 见各自单次上限 |
| `pledge_stat` / `pledge_detail` | **1000/页** |
| `repurchase` | **2000/页** |
| `report_rc` | 3000/页（但受 1 次/分钟限制，实际只拉 1 页） |
| `disclosure_date` | 6000/页（单期约 5400 行，1 页完成） |
| `fina_mainbz` | `ts_code` 必填；单次返回可达 150 行全历史，`offset` 分页无意义；按 per-stock 全历史拉取（保留 `MAX_PAGES` 保护） |
| `index_member_all` | 2000/页；按 L1 行业代码（如 `801010.SI`）循环拉取 |

### 3.3 批量写入与幂等

HTTP 拉取与数据库写入要解耦：

1. **不要把一次 HTTP 请求包在数据库事务里**；限流等待会长期占用连接。
2. 按页/按批开短事务。
3. 批量写入建议 **500 行/批**，不要逐行 INSERT。
4. 幂等统一用「先删后插」或「upsert」：
   - 按行业务键删除，再批量插入；
   - 或使用数据库唯一键 `ON CONFLICT / ON DUPLICATE KEY UPDATE`。
5. 流式落库时，删除条件必须是**行业务键**，不能按 `ts_code` 全删，否则会把前面页的数据删掉。

三种典型幂等粒度：

| 粒度 | 适用 | 示例 |
|---|---|---|
| 行业务主键 | 行情、明细、事件 | `daily(ts_code, trade_date)` |
| 公告/报告期整块 | 同一公告多行、整期快照 | `repurchase(ts_code, ann_date)`、`top10_holders(ts_code, end_date)` |
| 全量替换 | 维表 | `index_basic` 全表重建、`sw_industry` 按 `src` 重建 |

### 3.4 并发

- 财务类 per-stock 接口（`income`、`balancesheet`、`cashflow`、`fina_indicator`）可并发拉取，但并发度建议 ≤30，并与限流叠加使用。
- 行情/事件按交易日拉全市场时，天然是串行逐日，不需要高并发。
- 单股失败不要中断整体时，记录告警并继续；关键步骤（行情主表）建议失败即停、保留已写入进度。

---

## 4. 五种更新策略（全量与增量）

| 策略 | 适用接口 | 全量起点 | 增量起点 |
|---|---|---|---|
| 一次性全量 | `stock_basic`、`index_basic`、`index_classify`、`index_member_all`（按 L1 行业代码循环） | — | 每次全量覆盖 |
| per-stock | 财务表（含 `fina_mainbz` 单股全历史）、`pledge_detail`、`top10_*`、`stk_holdernumber`、`dividend`、`stk_holdertrade` | `19901219` | 各自 `MAX(ann_date)` / 近 1 年 |
| per-trade-date | `daily`、`adj_factor`、`daily_basic`、`moneyflow`、`top_list`、`top_inst`、`block_trade`、`hk_hold`（本项目已停用每日作业）、`margin`、`margin_detail` | `19901219` | `MAX(trade_date)`；空表回退 `incremental-fallback-days`（默认 365 天） |
| per-month | `stk_limit`、`stk_weekly_monthly`、`share_float`（全量）、`repurchase`（全量）、`suspend_d` | `19901219` / 业务起点 | `MAX(trade_date/ann_date)` |
| per-period / 公告日 | `disclosure_date`、`pledge_stat`、`report_rc`、`dividend`（补录）、`share_float`（增量） | `19901219` / `2015` | `MAX(end_date)` / `MAX(ann_date)` / `MAX(report_date)` |

按交易日/月更新时，天然支持断点续跑：每次从本地 `MAX(date)` 开始，失败重跑不会产生重复数据。

---

## 5. 接口总览表（40 个接口 / 41 张表）

> 限流单位：次/分钟。

| # | api_name | 数据表 | 幂等键 | 拉取维度 | 页大小 | 限流 |
|---|---|---|---|---|---|---|
| 1 | `stock_basic` | `stock_basic` | `ts_code` | 一次性（L/D/P） | 5000 | 500 |
| 2 | `trade_cal` | `trade_cal` | `(exchange, cal_date)` | 按交易所区间 | 5000 | 500 |
| 3 | `namechange` | `stock_namechange` | `(ts_code, start_date)` | 区间（稀疏事件） | 5000 | 200 |
| 4 | `daily` | `daily` | `(ts_code, trade_date)` | 按交易日 | 5000 | 500 |
| 5 | `adj_factor` | `adj_factor` | `(ts_code, trade_date)` | 按交易日 | 5000 | 200 |
| 6 | `daily_basic` | `daily_basic` | `(trade_date, ts_code)` | 按交易日 | 10000 | 180 |
| 7 | `moneyflow` | `stock_moneyflow` | `(ts_code, trade_date)` | 按交易日 | 10000 | 180 |
| 8 | `stk_limit` | `stock_stk_limit` | `(ts_code, trade_date)` | 按月窗口 | 5000 | 180 |
| 9 | `stk_weekly_monthly`（week） | `stock_weekly_quote` | `(ts_code, trade_date)` | 按月窗口 | 5000 | 200 |
| 10 | `stk_weekly_monthly`（month） | `stock_monthly_quote` | `(ts_code, trade_date)` | 按月窗口 | 5000 | 200 |
| 11 | `dividend` | `dividend` | `(ts_code, end_date, ann_date)` | per-stock / 逐公告日 | 默认 | 180 |
| 12 | `fina_indicator` | `fina_indicator` | `(ts_code, end_date)` | per-stock | 默认 | 150 |
| 13 | `income` | `income` | `(ts_code, end_date, report_type)` | per-stock | 默认 | 200 |
| 14 | `balancesheet` | `balancesheet` | `(ts_code, end_date, report_type)` | per-stock | 默认 | 200 |
| 15 | `cashflow` | `cashflow` | `(ts_code, end_date, report_type)` | per-stock | 默认 | 200 |
| 16 | `forecast` | `forecast` | `(ts_code, end_date, ann_date)` | per-stock / 逐公告日 | 默认 | 200 |
| 17 | `express` | `express` | `(ts_code, end_date)` | per-stock / 逐公告日 | 默认 | 190 |
| 18 | `fina_audit` | `fina_audit` | `(ts_code, end_date, ann_date)` | per-stock | 默认 | 150 |
| 19 | `fina_mainbz` | `fina_mainbz` | `(ts_code, end_date, bz_item, bz_code)` | per-stock 全历史（`ts_code` 必填） | 100 | 150 |
| 20 | `disclosure_date` | `disclosure_date` | `(ts_code, end_date)` | 按报告期全市场 | 6000 | 300 |
| 21 | `top_list` | `top_list` | `(trade_date, ts_code, name, reason)` | 按交易日 | 默认 | 180 |
| 22 | `top_inst` | `top_inst` | `(trade_date, ts_code, exalter, side)` | 按交易日 | 默认 | 180 |
| 23 | `block_trade` | `block_trade` | `(trade_date, ts_code, buyer, seller)` | 按交易日 | 默认 | 180 |
| 24 | `suspend_d` | `stock_suspend_d` | `(ts_code, trade_date, suspend_type)` | 按月 + 降级 per-stock | 5000 | 200 |
| 25 | `share_float` | `share_float` | `(ts_code, float_date, holder_name)` | 全量按月 / 增量逐公告日 | 5000 | 60 |
| 26 | `pledge_stat` | `pledge_stat` | `(ts_code, end_date)` | 按周五 | **1000** | 60 |
| 27 | `pledge_detail` | `pledge_detail` | `(ts_code, ann_date)` 整公告 | per-stock | 1000 | 60 |
| 28 | `repurchase` | `repurchase` | `(ts_code, ann_date)` 整公告 | 区间 / 按月 | 2000 | 60 |
| 29 | `top10_holders` | `top10_holders` | `(ts_code, end_date)` 整期 | per-stock | 5000 | 120 |
| 30 | `top10_floatholders` | `top10_floatholders` | `(ts_code, end_date)` 整期 | per-stock | 5000 | 120 |
| 31 | `stk_holdertrade` | `stk_holdertrade` | 业务六元组 | per-stock | 5000 | 180 |
| 32 | `stk_holdernumber` | `stk_holdernumber` | `(ts_code, end_date)` | per-stock | 5000 | 180 |
| 33 | `report_rc` | `report_rc` | `(ts_code, report_date, org_name)` | 区间 | 3000 | **1** |
| 34 | `index_basic` | `index_basic` | `ts_code`（全量替换） | 一次性 | 5000 | 500 |
| 35 | `index_daily` | `index_daily` | `(ts_code, trade_date)` | per-index | 5000 | 500 |
| 36 | `index_weight` | `index_weight` | `(index_code, trade_date, con_code)` | per-index 区间单次（参数 `index_code`） | 默认 | 200 |
| 37 | `index_classify` | `sw_industry` | `(index_code, src)` 全量替换 | 一次性（`src=SW2021`） | — | 200 |
| 38 | `index_member_all` | `sw_industry_member` | 幂等键不适用（全量替换） | 按 L1 行业代码循环全量 | 2000 | 200 |
| 39 | `hk_hold` | `hk_hold` | `(trade_date, code)` | 按交易日 | 10000 | 180 |
| 40 | `margin` | `margin` | `(exchange_id, trade_date)` | 按交易日 | 默认 | 180 |
| 41 | `margin_detail` | `margin_detail` | `(trade_date, ts_code)` | 按交易日 | 10000 | 180 |

> **字段实测修正（本表「拉取维度/幂等键」未列字段，以附录 A 为准）**：`express` 实为 15 列（无 `basic_eps`/`growth_yield`/`or_growth_yield` 等，含 `diluted_eps`/`diluted_roe`/`yoy_net_profit`/`bps`/`perf_summary`）；`block_trade` 实为 7 列（无 `name`/`buyer_name`/`seller_name`）；`index_classify` 关键列为 `index_code,industry_name,level,industry_code,is_pub,parent_code,src`（无 `index_name`）；`index_member_all` 为 11 列（`l1_code`…`is_new`，无 `index_name`/`update_date`）；`hk_hold` 列名为 `exchange`（非 `exchange_id`）；`index_weight` 参数为 `index_code`、列含 `index_code` 无 `ts_code`。
>
> **单位口径实测**：`stk_weekly_monthly.vol` 单位=**手**（落库不换算）；`index_daily.vol`=手、`amount`=千元（与 `daily` 同口径，不换算），详见第 7 章。

---

## 6. 逐接口契约

> 每张卡片包含：查询参数、返回字段口径、拉取方式、写入幂等键、频控与注意点。
> 完整 `fields` 串见附录 A。

### 6.1 基础数据

#### `stock_basic` → `stock_basic`

| 项 | 内容 |
|---|---|
| 用途 | 股票基础信息（代码、名称、行业、上市状态、上市/退市日期） |
| 查询参数 | `ts_code`、`name`、`market`、`list_status`（`L` 上市 / `D` 退市 / `P` 暂停）、`exchange`、`is_hs`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`symbol`、`name`、`area`、`industry`、`market`、`exchange`、`list_status`、`list_date`、`delist_date`、`is_hs` |
| 拉取方式 | 对 `L`、`D`、`P` 三种状态分别分页，5000/页，最多 10 页 |
| 幂等键 | `ts_code` |
| 频控 | 500 次/分钟 |
| 注意 | 必须同时拉退市/暂停股票；无日期参数；每次全量刷新；后续所有 per-stock 任务的股票清单都来自本表 |

#### `trade_cal` → `trade_cal`

| 项 | 内容 |
|---|---|
| 用途 | 交易日历 |
| 查询参数 | `exchange`（`SSE` / `SZSE`）、`start_date`、`end_date`、`is_open`、`offset`、`limit` |
| 关键返回字段 | `exchange`、`cal_date`、`is_open`（1/0）、`pretrade_date` |
| 拉取方式 | 两个交易所各拉一次；5000/页，最多 10 页 |
| 幂等键 | `(exchange, cal_date)` |
| 频控 | 500 次/分钟 |
| 注意 | 全量从 `19901219` 起，增量从 `MAX(cal_date)` 起；调仓日标记（周/月/季首末日）是本地派生，不由接口返回 |

#### `namechange` → `stock_namechange`

| 项 | 内容 |
|---|---|
| 用途 | 股票更名历史（ST 戴帽/摘帽） |
| 查询参数 | `ts_code`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`name`、`start_date`（生效）、`end_date`（失效，空=当前）、`change_reason` |
| 拉取方式 | 5000/页分页；全量不带日期，增量 `MAX(start_date) ~ today` 一次区间查询 |
| 幂等键 | `(ts_code, start_date)` |
| 频控 | 200 次/分钟 |
| 注意 | 稀疏事件，**不要按交易日逐日拉**；流式写入必须按行业务键删，不能按股票全删 |

### 6.2 行情数据

#### `daily` → `daily`

| 项 | 内容 |
|---|---|
| 用途 | 日线行情 |
| 查询参数 | `ts_code`、`trade_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`trade_date`、`open/high/low/close/pre_close/change/pct_chg`、`vol`（手）、`amount`（千元） |
| 主拉取方式 | 按交易日全市场：单日约 5000+ 行，1~2 页；`limit=5000` |
| 备用方式 | per-stock：从本地最新日期（含当日）到今日，5000/页，最多 100 页 |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 500 次/分钟 |
| 注意 | 全市场单日会超过 5000 行，`limit` 必须 ≥5000；日常增量不要 per-stock（5000+ 请求） |

#### `adj_factor` → `adj_factor`

| 项 | 内容 |
|---|---|
| 用途 | 复权因子 |
| 查询参数 | `ts_code`、`trade_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`trade_date`、`adj_factor` |
| 拉取方式 | 主路径按交易日全市场；备用 per-stock，从本地最新日期（含当日）起，5000/页，最多 100 页 |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 200 次/分钟 |
| 注意 | 接口按 `trade_date` 倒序返回；不要假设分页结果升序；复权因子只在除权日变化，但日常仍按日补齐 |

#### `daily_basic` → `daily_basic`

| 项 | 内容 |
|---|---|
| 用途 | 每日估值/换手/市值 |
| 查询参数 | `trade_date`、`ts_code`、`limit` |
| 关键返回字段 | `close`、`turnover_rate`、`turnover_rate_f`、`volume_ratio`、`pe`、`pe_ttm`、`pb`、`ps`、`ps_ttm`、`dv_ratio`、`dv_ttm`、`total_share`、`float_share`、`free_share`、`total_mv`、`circ_mv` |
| 拉取方式 | 按交易日全市场，`limit=10000` |
| 幂等键 | `(trade_date, ts_code)` |
| 频控 | 180 次/分钟 |
| 注意 | 单日约 5300 行，默认 5000 会截断；停牌股无记录；亏损股 `pe/pb` 可能为空 |

#### `moneyflow` → `stock_moneyflow`

| 项 | 内容 |
|---|---|
| 用途 | 个股资金流向（小/中/大/特大单） |
| 查询参数 | `trade_date`、`ts_code`、`limit` |
| 关键返回字段 | 各档 `buy_*_amount`、`sell_*_amount`、`buy_*_vol`、`sell_*_vol`、`net_mf_amount`、`net_mf_vol` |
| 拉取方式 | 按交易日全市场，`limit=10000` |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 金额单位 **万元**，量单位 **万手**；单日约 5000+ 行 |

#### `stk_limit` → `stock_stk_limit`

| 项 | 内容 |
|---|---|
| 用途 | 每日涨跌停价 |
| 查询参数 | `ts_code`、`trade_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`trade_date`、`pre_close`、`up_limit`、`down_limit`（元） |
| 拉取方式 | **按月窗口**范围查询 + 5000/页 + 最多 100 页 |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 每日数据稀疏，按日查询会产生大量空请求；按月聚合最省配额；使用 upsert 保证可重跑 |

#### `stk_weekly_monthly` → `stock_weekly_quote` / `stock_monthly_quote`

| 项 | 内容 |
|---|---|
| 用途 | 周线 / 月线行情 |
| 查询参数 | **`freq` 必填**（`week` / `month`）、`ts_code`、`trade_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`trade_date`、`end_date`、`freq`、OHLC、`pre_close`、`vol`、`amount`、`change`、`pct_chg` |
| 拉取方式 | 按月窗口 + 5000/页，最多 100 页；`freq` 决定写入哪张表 |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 200 次/分钟 |
| 注意 | 过滤严格按 `trade_date`（周期最后交易日），**周中/月末前查不到新行是预期**；`vol` 实测单位=**手**（≈当周 5 日日线 `vol` 之和），落库**不换算**（×100 说法已实测否定）；`pct_chg` 已 ×100，不再换算 |

### 6.3 财务数据

#### `dividend` → `dividend`

| 项 | 内容 |
|---|---|
| 用途 | 分红送股 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date`、`record_date`、`ex_date`、`imp_ann_date` |
| 关键返回字段 | `end_date`、`ann_date`、`div_proc`、`stk_div`、`stk_bo_rate`、`stk_co_rate`、`cash_div`、`cash_div_tax`、`record_date`、`ex_date`、`pay_date`、`div_listdate`、`base_date`、`base_share` |
| 拉取方式 | 全量 per-stock；增量 per-stock 从各自 `MAX(ann_date)` 起；补录按公告日逐日全市场 |
| 幂等键 | `(ts_code, end_date, ann_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 公告稀疏，逐公告日要跳过空日；per-stock 与按公告日两条路径参数语义不同，不要混用 |

#### `fina_indicator` → `fina_indicator`

| 项 | 内容 |
|---|---|
| 用途 | 财务指标 |
| 查询参数 | `ts_code`、`start_date`、`end_date` |
| 关键返回字段 | `ann_date`、`end_date`、`roe`、`roa`、`grossprofit_margin`、`netprofit_margin`、`dt_netprofit_yoy`、`or_yoy`（营收同比）、`debt_to_assets`、`dt_eps_yoy`（EPS 同比） |
| 拉取方式 | per-stock 区间；全量 `19901219` 起，增量从各自 `MAX(ann_date)` 起（空表近 1 年） |
| 幂等键 | `(ts_code, end_date)` |
| 频控 | **150 次/分钟**（原未配置，T3 已补） |
| 注意 | 实测 `revenue_yoy`/`eps_yoy` 两字段不存在，已改用 `or_yoy`/`dt_eps_yoy`；并发建议 ≤30；本项目只取 11 个核心指标，需要更多指标时扩展 `fields` |

#### `income` / `balancesheet` / `cashflow` → 三张财务报表

| 项 | 内容 |
|---|---|
| 用途 | 利润表 / 资产负债表 / 现金流量表 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date`、`report_type`、`comp_type` |
| 关键返回字段 | 见附录 A（官方全量：`income` 94 列 / `balancesheet` 158 列 / `cashflow` 97 列）；含 `ann_date`、`f_ann_date`、`end_date`、`report_type`、`comp_type`、`end_type`、各科目金额、`update_flag` |
| 拉取方式 | per-stock 区间；全量 `19901219` 起，增量从各自 `MAX(ann_date)` 起（空表近 1 年） |
| 幂等键 | `(ts_code, end_date, report_type)` |
| 频控 | 各 200 次/分钟 |
| 注意 | 同一报告期会返回多条（更正），必须去重保留 `ann_date` 最新；`report_type` 1=合并、2=单季合并、3=调整单季、4=调整合并、5=调整前、6=调整后；`comp_type` 1=一般工商、2=证券、3=保险、4=银行；金额单位元 |

#### `forecast` → `forecast`

| 项 | 内容 |
|---|---|
| 用途 | 业绩预告 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date`、`period` |
| 关键返回字段 | `ann_date`、`end_date`、`type`、`p_change_min`、`p_change_max`、`net_profit_min`、`net_profit_max`、`last_parent_net`、`summary`、`change_reason` |
| 拉取方式 | per-stock 区间 + 逐公告日全市场补录 |
| 幂等键 | `(ts_code, end_date, ann_date)` |
| 频控 | 200 次/分钟 |
| 注意 | 同一报告期会有多次预告，唯一键必须含 `ann_date` |

#### `express` → `express`

| 项 | 内容 |
|---|---|
| 用途 | 业绩快报 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date`、`period` |
| 关键返回字段 | `ann_date`、`end_date`、`revenue`、`operate_profit`、`total_profit`、`n_income`、`total_assets`、`total_hldr_eqy_exc_min_int`、`diluted_eps`、`diluted_roe`、`yoy_net_profit`、`bps`、`perf_summary`、`update_flag` |
| 拉取方式 | per-stock 区间 + 逐公告日全市场 |
| 幂等键 | `(ts_code, end_date)` |
| 频控 | 190 次/分钟 |
| 注意 | 实测为 15 列（无 `basic_eps`/`growth_yield`/`or_growth_yield` 等）；近期返回 0 行属正常（业绩快报按公告季节性出现，不算失败）；通常一次定稿，唯一键不含 `ann_date` |

#### `fina_audit` → `fina_audit`

| 项 | 内容 |
|---|---|
| 用途 | 财务审计意见 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date`、`period`、`offset`、`limit` |
| 关键返回字段 | `ann_date`、`end_date`、`audit_result`、`audit_fees`、`audit_agency`、`audit_sign` |
| 拉取方式 | per-stock 区间；每股 10~30 条，单次 ≤5000，无需分页 |
| 幂等键 | `(ts_code, end_date, ann_date)` |
| 频控 | 150 次/分钟 |
| 注意 | `audit_result` 是中文结论文本，不要当枚举编码 |

#### `fina_mainbz` → `fina_mainbz`

| 项 | 内容 |
|---|---|
| 用途 | 主营业务构成 |
| 查询参数 | `ts_code`（必填）、`period`（可选，仅近期有效）、`type`、`start_date`、`end_date`、`offset`、`limit`；项目实现只传 `ts_code` 拉全历史 |
| 关键返回字段 | `end_date`、`bz_item`、`bz_code`、`bz_sales`、`bz_profit`、`bz_cost`、`curr_type`、`update_flag` |
| 拉取方式 | **per-stock 全历史**（`ts_code` 必填；单次返回可达 150 行全历史，保留 `MAX_PAGES` 保护） |
| 幂等键 | `(ts_code, end_date, bz_item, bz_code)` |
| 频控 | 150 次/分钟 |
| 注意 | 实测：仅传 `period` 报 50101（`ts_code` 必填）；「单次 100 行硬限制 / offset 分页无效 / 必须按季度末 `period` 循环」均不成立；`period` 仅近期有效（如 20250630 有数据、20240930 为 0）；单股失败只告警不中断整只股票 |

#### `disclosure_date` → `disclosure_date`

| 项 | 内容 |
|---|---|
| 用途 | 财报披露计划 |
| 查询参数 | `ts_code`、`end_date`、`pre_date`、`ann_date`、`actual_date`、`offset`、`limit` |
| 关键返回字段 | `ann_date`、`end_date`、`pre_date`、`actual_date`、`modify_date` |
| 拉取方式 | 全市场按 `end_date`（季度末）单点查询，`limit=6000` |
| 幂等键 | `(ts_code, end_date)` |
| 频控 | 300 次/分钟 |
| 注意 | 单期约 5400 行，1 页完成；不要 per-stock；`actual_date` 为空表示尚未披露 |

### 6.4 事件数据

#### `top_list` → `top_list`

| 项 | 内容 |
|---|---|
| 用途 | 龙虎榜每日榜单 |
| 查询参数 | `trade_date`、`ts_code` |
| 关键返回字段 | `trade_date`、`ts_code`、`name`、`close`、`pct_change`、`turnover_rate`、`amount`、`l_sell`、`l_buy`、`l_amount`、`net_amount`、`net_rate`、`amount_rate`、`float_values`、`reason` |
| 拉取方式 | 按交易日全市场 |
| 幂等键 | `(trade_date, ts_code, name, reason)`；表本身无主键 |
| 频控 | 180 次/分钟 |
| 注意 | 同股同原因可能返回多条不同口径；删除键含 `name/reason`，上游改名/改原因会残留旧行，需定期按日重建 |

#### `top_inst` → `top_inst`

| 项 | 内容 |
|---|---|
| 用途 | 龙虎榜营业部席位 |
| 查询参数 | `trade_date`、`ts_code` |
| 关键返回字段 | `trade_date`、`ts_code`、`exalter`、`side`（0 买入前 5 / 1 卖出前 5）、`buy`、`buy_rate`、`sell`、`sell_rate`、`net_buy` |
| 拉取方式 | 按交易日全市场 |
| 幂等键 | `(trade_date, ts_code, exalter, side)` |
| 频控 | 180 次/分钟 |
| 注意 | 同股同日买卖席位各多行 |

#### `block_trade` → `block_trade`

| 项 | 内容 |
|---|---|
| 用途 | 大宗交易 |
| 查询参数 | `trade_date`、`ts_code` |
| 关键返回字段 | `ts_code`、`trade_date`、`price`（元）、`vol`（万股）、`amount`（万元）、`buyer`、`seller` |
| 拉取方式 | 按交易日全市场 |
| 幂等键 | `(trade_date, ts_code, buyer, seller)` |
| 频控 | 180 次/分钟 |
| 注意 | 实测 7 列（无 `name`/`buyer_name`/`seller_name`）；同股同日可多笔（不同买卖营业部/价格） |

#### `suspend_d` → `stock_suspend_d`

| 项 | 内容 |
|---|---|
| 用途 | 停复牌 |
| 查询参数 | `ts_code`、`trade_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`trade_date`、`suspend_timing`（空=全天）、`suspend_type`（`S` 停牌 / `R` 复牌） |
| 拉取方式 | **按月拆分** + 5000/页；单月 `offset ≥ 100000` 时降级为 per-stock |
| 幂等键 | `(ts_code, trade_date, suspend_type)` |
| 频控 | 200 次/分钟 |
| 注意 | 全表约 70 万行，`offset` 上限 10 万；按月后单月约 2000 行；同日可同时有 S/R |

#### `share_float` → `share_float`

| 项 | 内容 |
|---|---|
| 用途 | 限售股解禁 |
| 查询参数 | `ts_code`、`ann_date`、`float_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ann_date`、`float_date`、`float_share`（股）、`float_ratio`（%）、`holder_name`、`share_type` |
| 拉取方式 | 全量按月窗口（起点 `20060101`）；增量逐公告日 `ann_date`（从 `MAX(ann_date)` 起） |
| 幂等键 | `(ts_code, float_date, holder_name)` |
| 频控 | 60 次/分钟 |
| 注意 | 一次公告按股东逐行展开；同一事件会被预告/正式多次公告，页内去重保留最新 `ann_date`；`start_date/end_date` 对应 `float_date`，不要与 `ann_date` 混用 |

#### `pledge_stat` → `pledge_stat`

| 项 | 内容 |
|---|---|
| 用途 | 股权质押统计（周度快照） |
| 查询参数 | 仅 `ts_code`、`end_date`（**无区间参数**） |
| 关键返回字段 | `end_date`、`pledge_count`、`unrest_pledge`、`rest_pledge`、`total_share`、`pledge_ratio` |
| 拉取方式 | 按**周五** `end_date` 全市场；周五无数据回补前一天；全量 `20150102` 起 |
| 分页 | **1000/页**（单次上限 1000），全市场单周约 5400 行 → 6 页 |
| 幂等键 | `(ts_code, end_date)` |
| 频控 | 60 次/分钟 |
| 注意 | 传 `limit=5000` 会因接口截断且提前终止导致**漏数据** |

#### `pledge_detail` → `pledge_detail`

| 项 | 内容 |
|---|---|
| 用途 | 股权质押明细 |
| 查询参数 | 仅 `ts_code` |
| 关键返回字段 | `ann_date`、`holder_name`、`pledge_amount`、`start_date`、`end_date`、`is_release`、`release_date`、`pledgor`、`holding_amount`、`pledged_amount`、`p_total_ratio`、`h_total_ratio`、`is_buyback` |
| 拉取方式 | 只能 per-stock 全历史（接口无日期过滤），增量=全量；1000/页 |
| 幂等键 | `(ts_code, ann_date)` 整公告替换 |
| 频控 | 60 次/分钟 |
| 注意 | 全市场 5000+ 只 × 多页，耗时长，适合低频全量；`is_release/is_buyback` 为 `0/1` |

#### `repurchase` → `repurchase`

| 项 | 内容 |
|---|---|
| 用途 | 股票回购 |
| 查询参数 | `ann_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ann_date`、`end_date`、`proc`、`exp_date`、`vol`、`amount`、`high_limit`、`low_limit` |
| 拉取方式 | 全量按月窗口（起点 `20150101`）；增量 `MAX(ann_date) ~ today` 一次区间查询 |
| 分页 | **2000/页**（接口默认单次 2000，步进必须一致） |
| 幂等键 | `(ts_code, ann_date)` 整公告替换 |
| 频控 | 60 次/分钟 |
| 注意 | 同股同日多行（不同进度/价格区间），不能按行做主键；`proc` 是中文进度文本 |

#### `top10_holders` / `top10_floatholders`

| 项 | 内容 |
|---|---|
| 用途 | 前十大股东 / 前十大流通股东 |
| 查询参数 | `ts_code`（必填）、`period`、`ann_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `ann_date`、`end_date`、`holder_name`、`hold_amount`（股）、`hold_ratio`（%）、`hold_float_ratio`（%）、`hold_change`（股）、`holder_type` |
| 拉取方式 | per-stock 全历史（增量=全量）；5000/页 |
| 幂等键 | `(ts_code, end_date)` 整期快照替换 |
| 频控 | 各 120 次/分钟 |
| 注意 | 两接口字段同构、口径不同（占总股本 vs 占流通股），必须分表；`ts_code` 必填 |

#### `stk_holdertrade` → `stk_holdertrade`

| 项 | 内容 |
|---|---|
| 用途 | 股东增减持 |
| 查询参数 | `ts_code`、`ann_date`、`start_date`、`end_date` |
| 关键返回字段 | `ann_date`、`holder_name`、`holder_type`、`in_de`（`IN`/`DE`）、`change_vol`、`change_ratio`、`after_share`、`after_ratio`、`avg_price`、`total_share`、`begin_date`、`close_date` |
| 拉取方式 | per-stock + 日期区间；5000/页 |
| 幂等键 | `(ts_code, ann_date, holder_name, in_de, begin_date, close_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 同一股东一次公告可能多行；写入前必须先按六元组去重 |

#### `stk_holdernumber` → `stk_holdernumber`

| 项 | 内容 |
|---|---|
| 用途 | 股东人数 |
| 查询参数 | 仅 `ts_code` |
| 关键返回字段 | `ann_date`、`end_date`、`holder_num` |
| 拉取方式 | per-stock 全历史（增量=全量）；5000/页 |
| 幂等键 | `(ts_code, end_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 全市场每次刷新约 5000 次请求，建议每周低频跑 |

#### `report_rc` → `report_rc`

| 项 | 内容 |
|---|---|
| 用途 | 券商盈利预测 |
| 查询参数 | `ts_code`、`report_date`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `report_date`、`report_title`、`report_type`、`classify`、`org_name`、`author_name`、`quarter`、`op_rt/op_pr/tp/np`（万元）、`eps`、`pe`、`rd`、`roe`、`ev_ebitda`、`rating`、`max_price`、`min_price`、`imp_dg` |
| 拉取方式 | 区间查询；仅回补近 90 天；增量 `MAX(report_date) ~ today` |
| 分页 | 设计上 3000/页，但受频次限制实际只拉 1 页 |
| 幂等键 | `(ts_code, report_date, org_name)` |
| 频控 | **1 次/分钟**（试用档每天 10 次） |
| 注意 | 翻第二页会立即 `40203`；`quarter` 是预测报告期（如 `2026Q4`），同一研报多期预测各一行 |

### 6.5 指数与市场

#### `index_basic` → `index_basic`

| 项 | 内容 |
|---|---|
| 用途 | 指数基础信息 |
| 查询参数 | `ts_code`、`market`、`publisher`、`category`、`offset`、`limit` |
| 关键返回字段 | `ts_code`、`name`、`fullname`、`market`、`publisher`、`index_type`、`category`、`base_date`、`base_point`、`list_date`、`weight_rule` |
| 拉取方式 | 一次性全市场；5000/页，最多 10 页 |
| 幂等键 | `ts_code`，全量替换 |
| 频控 | 500 次/分钟 |
| 注意 | 是 `index_daily`、`index_weight` 的指数代码来源，必须先跑；表为空时会回退到内置核心宽基清单 |

#### `index_daily` → `index_daily`

| 项 | 内容 |
|---|---|
| 用途 | 指数日线 |
| 查询参数 | `ts_code`、`start_date`、`end_date`、`offset`、`limit` |
| 关键返回字段 | `trade_date`、`close/open/high/low/pre_close/change/pct_chg`、`vol`、`amount` |
| 拉取方式 | per-index 区间；每个指数 5000/页，最多 10 页 |
| 幂等键 | `(ts_code, trade_date)` |
| 频控 | 500 次/分钟 |
| 注意 | 单指数 30 年约 7500 行需分页；全量起始取 `index_basic.base_date`；实测 `vol`=手、`amount`=千元，与 `daily` 同口径，落库不换算 |

#### `index_weight` → `index_weight`

| 项 | 内容 |
|---|---|
| 用途 | 指数成分与权重 |
| 查询参数 | **`index_code`（指数代码，必填）**、`trade_date`、`start_date`、`end_date` |
| 关键返回字段 | `index_code`、`trade_date`、`con_code`、`weight`（%） |
| 拉取方式 | per-index 区间（`start_date/end_date`），5000/页最多 100 页；指数清单来自 `sw_industry` 的 L1 行业代码 |
| 幂等键 | `(index_code, trade_date, con_code)` |
| 频控 | 200 次/分钟 |
| 注意 | 实测必填参数是 `index_code`（传 `ts_code` 报 50101）；列名为 `index_code` 无 `ts_code`；权重按月/半年更新，不是每日；删除粒度是「某指数某交易日的全部成分」，不能按行删 |

#### `index_classify` + `index_member_all` → 行业分类与成分

| 项 | 内容 |
|---|---|
| 用途 | 申万行业分类 / 行业成分股 |
| 查询参数 | classify：`src`（固定 `SW2021`）、`level`；member：`index_code`（必填，按 L1 行业代码循环，如 `801010.SI`）、`src`（实测传 `SW2021` 返回 0 行） |
| 关键返回字段 | classify：`index_code`、`industry_name`、`level`、`industry_code`、`is_pub`、`parent_code`、`src`；member：`l1_code`、`l1_name`、`l2_code`、`l2_name`、`l3_code`、`l3_name`、`ts_code`、`name`、`in_date`、`out_date`、`is_new` |
| 拉取方式 | classify 一次性；member 按 L1 行业代码逐个全量拉取，2000/页，写入用**全量替换** |
| 幂等键 | classify `(index_code, src)` 全量替换；member 幂等键不适用（全量替换） |
| 频控 | 各 200 次/分钟 |
| 注意 | 行业是层级结构（1/2/3 级）；member 实测无 `index_code`/`index_name`/`update_date` 列，也无快照日概念；`index_code=801010.SI` 单次约 5919 行（SW2021 全量成分）；member 传 5000 可能截断 |

#### `hk_hold` → `hk_hold`

| 项 | 内容 |
|---|---|
| 用途 | 沪深港通持股 |
| 查询参数 | `trade_date`、`ts_code`、`limit` |
| 关键返回字段 | `trade_date`、`code`、`name`、`vol`（万股）、`ratio`（%）、`ts_code`、`exchange`（`SH`/`SZ`，实测列名非 `exchange_id`） |
| 拉取方式 | 按交易日全市场，`limit=10000` |
| 幂等键 | `(trade_date, code)` |
| 频控 | 180 次/分钟 |
| 注意 | T+1 更新；`code` 与 `ts_code` 可能一个为空，主键用 `code` |

#### `margin` → `margin`

| 项 | 内容 |
|---|---|
| 用途 | 融资融券汇总 |
| 查询参数 | `trade_date`、`exchange_id`（`SSE` / `SZSE`） |
| 关键返回字段 | `exchange_id`、`trade_date`、`rzye`、`rzmre`、`rzche`、`rqye`、`rqmcl`、`rzrqye` |
| 拉取方式 | 按交易日；不传 `exchange_id` 返回两所各一行 |
| 幂等键 | `(exchange_id, trade_date)` |
| 频控 | 180 次/分钟 |
| 注意 | 金额单位万元，`rqmcl` 万股；`exchange_id` 是 `SSE/SZSE`，与 `hk_hold` 的 `SH/SZ` 不同 |

#### `margin_detail` → `margin_detail`

| 项 | 内容 |
|---|---|
| 用途 | 融资融券个股明细 |
| 查询参数 | `trade_date`、`ts_code`、`limit` |
| 关键返回字段 | `trade_date`、`ts_code`、`name`、`rzye`、`rqye`、`rzmre`、`rzche`、`rqmcl`、`rzrqye` |
| 拉取方式 | 按交易日全市场，`limit=10000` |
| 幂等键 | `(trade_date, ts_code)` |
| 频控 | 180 次/分钟 |
| 注意 | 单日约 3880 行，临界 5000，必须显式放大 |

---

## 7. 单位与口径速查

| 数据 | 单位 |
|---|---|
| 日线 `vol` / `amount` | 手 / 千元 |
| `daily_basic.total_mv`、`circ_mv` | 万元 |
| `daily_basic.total_share`、`float_share`、`free_share` | 万股 |
| 资金流金额 / 量 | 万元 / 万手 |
| 涨跌停价 | 元 |
| 周/月线 `vol` | **手**（实测，落库不换算；×100 说法已实测否定） |
| 周/月线 `amount` | 千元 |
| 大宗交易 `vol` / `amount` | 万股 / 万元 |
| 沪深港通 `vol` | 万股 |
| 融资融券金额 / `rqmcl` | 万元 / 万股 |
| 限售解禁 `float_share` | 股 |
| 质押统计与明细 | 万股；比例为 % |
| 前十大股东 `hold_amount` / `hold_change` | 股 |
| 券商预测 `op_rt/op_pr/tp/np` | 万元 |
| 指数权重 `weight` | % |
| 财务三表金额 | 元 |
| 指数日线 `vol` / `amount` | **手 / 千元**（实测与 `daily` 同口径，落库不换算） |

---

## 8. 已知限制与避坑清单

| # | 限制/坑 | 影响 | 正确做法 |
|---|---|---|---|
| 1 | `report_rc` 1 次/分钟 + 10 次/天 | 分页第二页即报错 | 区间一次拉完，不做多页 |
| 2 | `pledge_stat` 单次 1000 行 | 传 5000 会漏数据 | 固定 1000/页 |
| 3 | `repurchase` 单次 2000 行 | 传 5000 会提前终止 | 固定 2000/页 |
| 4 | `fina_mainbz` 必须带 `ts_code` | 仅传 `period` 报 50101 | per-stock 全历史拉取；单次可达 150 行，「100 行硬限制 / 必须按季度末 period 循环」已实测否定 |
| 5 | `suspend_d` offset 上限 10 万 | 全量翻页会失败 | 按月拆 + 超限降级 per-stock |
| 6 | 周/月线周期末才定稿 | 周中/月末前无新行 | 视为正常，不要重试 |
| 7 | 周/月线 `vol` 单位 | 原以为返回万股、需 ×100 | 实测单位=**手**（与日线同口径），落库不换算 |
| 8 | 财务同报告期多版本 | 重复/覆盖 | 按 `(ts_code,end_date,report_type)` 去重保留最新 `ann_date` |
| 9 | 流式分页按 `ts_code` 全删 | 会删掉前页数据 | 按行业务键删 |
| 10 | `top_list` 无主键 | 改名/改原因后残留 | 按 `trade_date+ts_code+name+reason` 删，定期按日重建 |
| 11 | `fina_indicator` 原未配置限流 | 可能 40203 | 已补 150 次/分钟（`ratelimit.yml`） |
| 12 | 指数日线单位 | 已实测确认 `vol`=手、`amount`=千元 | 落库不换算，与 `daily` 同口径 |
| 13 | 全市场接口默认 5000 | 单日超 5000 被截断 | 显式 `limit=10000/6000/3000/2000/1000` |
| 14 | 把 HTTP 请求包进数据库事务 | 限流等待占用连接 | HTTP 与写库解耦，按页短事务 |

---

## 9. 对接一个新接口的通用流程

1. 在 Tushare 官方文档确认 `api_name`、参数名、返回字段、单次上限、频次。
2. 定义查询参数对象：业务参数 + `offset` + `limit`，全部可选，只传非空值。
3. 定义返回结构：按 `fields` 逐字段映射，保持原始下划线名。
4. 配置该 `api_name` 的限流（分钟级），按 10%~25% 余量取值。
5. 设计存储表：主键/唯一键必须能表达业务唯一性，并列出幂等键。
6. 选择拉取维度：一次性 / per-stock / per-trade-date / per-month / per-period。
7. 设计全量起点与增量起点（`MAX(业务日期)`，空表回退固定回溯期）。
8. 实现分页：标准 offset 循环或接口专用规则，必须有页数/offset 上限保护。
9. 实现写库：HTTP 在事务外；按页短事务；500 行/批；先删后插或 upsert。
10. 处理缺失/脏数据：主键为空的行丢弃；单股/单期失败记录后继续（关键表可失败即停）。
11. 记录拉取日志：任务 ID、表、类型、状态、耗时、成功行数、错误摘要（错误摘要脱敏且 ≤1024 字符）。
12. 验收：用 curl/HTTP 客户端跑单接口 → 跑增量 → 跑全量 → 校验行数、最新日期、主键重复、单位口径。

**初始全量回填规模参考**（按附录 B 限流约束估算）：`daily`/`daily_basic`/`adj_factor` 等按日全市场表约 30~60 分钟；`fina_indicator` per-stock 约 35 分钟；`pledge_detail`、`stk_holdernumber` 等低频 per-stock 表各 30~90 分钟。全量回填总计约 **3~5 小时**，首次部署建议分晚执行，逐表进度记录于 `ingest_log`；未完成回填前流水线 S0 数据门会正常拦截，属预期行为。

**自查清单**

- [ ] `api_name`、参数名、`fields` 与官方逐字一致
- [ ] 全市场接口显式放大 `limit`
- [ ] 分页有 `MAX_PAGES` / offset 上限
- [ ] 稀疏事件不按日空跑
- [ ] 单次上限特殊的接口按特殊页大小处理
- [ ] 限流已配置（含遗漏项）
- [ ] HTTP 不在数据库事务内
- [ ] 批量写入 500 行/批
- [ ] 幂等键可覆盖重跑
- [ ] 单位换算与口径已核对
- [ ] 全量起点/增量起点明确
- [ ] 拉取日志与错误摘要已实现

---

## 附录 A：全部接口 `fields` 字段串

> 直接作为请求体 `fields` 参数使用，逗号分隔，不要改字段名。
>
> **通用契约特性（实测）**：Tushare 响应 `data.fields` 是请求 `fields` 的**子集**——整列全 NULL 的列会被省略、无效字段名会被静默丢弃。因此解析必须按**响应 `fields` 下标对齐**，缺失列一律按 NULL 落库，不可假设「请求列 = 响应列」。
>
> 下表 `income` / `balancesheet` / `cashflow` 为**官方文档全量字段串**（分别为 94 / 158 / 97 列），其余为 Step 0 真实 token 实测校正后的字段串。

| api_name | fields |
|---|---|
| `daily` | `ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount` |
| `stock_basic` | `ts_code,symbol,name,area,industry,fullname,enname,cnspell,market,exchange,curr_type,list_status,list_date,delist_date,is_hs,act_name,act_ent_type` |
| `trade_cal` | `exchange,cal_date,is_open,pretrade_date` |
| `adj_factor` | `ts_code,trade_date,adj_factor` |
| `dividend` | `ts_code,end_date,ann_date,div_proc,stk_div,stk_bo_rate,stk_co_rate,cash_div,cash_div_tax,record_date,ex_date,pay_date,div_listdate,imp_ann_date,base_date,base_share` |
| `daily_basic` | `ts_code,trade_date,close,turnover_rate,turnover_rate_f,volume_ratio,pe,pe_ttm,pb,ps,ps_ttm,dv_ratio,dv_ttm,total_share,float_share,free_share,total_mv,circ_mv` |
| `fina_indicator` | `ts_code,ann_date,end_date,roe,roa,grossprofit_margin,netprofit_margin,dt_netprofit_yoy,or_yoy,debt_to_assets,dt_eps_yoy` |
| `index_weight` | `index_code,con_code,trade_date,weight` |
| `index_classify` | `index_code,industry_name,level,industry_code,is_pub,parent_code,src` |
| `index_member_all` | `l1_code,l1_name,l2_code,l2_name,l3_code,l3_name,ts_code,name,in_date,out_date,is_new` |
| `namechange` | `ts_code,name,start_date,end_date,change_reason` |
| `suspend_d` | `ts_code,trade_date,suspend_timing,suspend_type` |
| `stk_limit` | `ts_code,trade_date,pre_close,up_limit,down_limit` |
| `income` | `ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,end_type,basic_eps,diluted_eps,total_revenue,revenue,int_income,prem_earned,comm_income,n_commis_income,n_oth_income,n_oth_b_income,prem_income,out_prem,une_prem_reser,reins_income,n_sec_tb_income,n_sec_uw_income,n_asset_mg_income,oth_b_income,fv_value_chg_gain,invest_income,ass_invest_income,forex_gain,total_cogs,oper_cost,int_exp,comm_exp,biz_tax_surchg,sell_exp,admin_exp,fin_exp,assets_impair_loss,prem_refund,compens_payout,reser_insur_liab,div_payt,reins_exp,oper_exp,compens_payout_refu,insur_reser_refu,reins_cost_refund,other_bus_cost,operate_profit,non_oper_income,non_oper_exp,nca_disploss,total_profit,income_tax,n_income,n_income_attr_p,minority_gain,oth_compr_income,t_compr_income,compr_inc_attr_p,compr_inc_attr_m_s,ebit,ebitda,insurance_exp,undist_profit,distable_profit,rd_exp,fin_exp_int_exp,fin_exp_int_inc,transfer_surplus_rese,transfer_housing_imprest,transfer_oth,adj_lossgain,withdra_legal_surplus,withdra_legal_pubfund,withdra_biz_devfund,withdra_rese_fund,withdra_oth_ersu,workers_welfare,distr_profit_shrhder,prfshare_payable_dvd,comshare_payable_dvd,capit_comstock_div,net_after_nr_lp_correct,credit_impa_loss,net_expo_hedging_benefits,oth_impair_loss_assets,total_opcost,amodcost_fin_assets,oth_income,asset_disp_income,continued_net_profit,end_net_profit,update_flag` |
| `balancesheet` | `ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,end_type,total_share,cap_rese,undistr_porfit,surplus_rese,special_rese,money_cap,trad_asset,notes_receiv,accounts_receiv,oth_receiv,prepayment,div_receiv,int_receiv,inventories,amor_exp,nca_within_1y,sett_rsrv,loanto_oth_bank_fi,premium_receiv,reinsur_receiv,reinsur_res_receiv,pur_resale_fa,oth_cur_assets,total_cur_assets,fa_avail_for_sale,htm_invest,lt_eqt_invest,invest_real_estate,time_deposits,oth_assets,lt_rec,fix_assets,cip,const_materials,fixed_assets_disp,produc_bio_assets,oil_and_gas_assets,intan_assets,r_and_d,goodwill,lt_amor_exp,defer_tax_assets,decr_in_disbur,oth_nca,total_nca,cash_reser_cb,depos_in_oth_bfi,prec_metals,deriv_assets,rr_reins_une_prem,rr_reins_outstd_cla,rr_reins_lins_liab,rr_reins_lthins_liab,refund_depos,ph_pledge_loans,refund_cap_depos,indep_acct_assets,client_depos,client_prov,transac_seat_fee,invest_as_receiv,total_assets,lt_borr,st_borr,cb_borr,depos_ib_deposits,loan_oth_bank,trading_fl,notes_payable,acct_payable,adv_receipts,sold_for_repur_fa,comm_payable,payroll_payable,taxes_payable,int_payable,div_payable,oth_payable,acc_exp,deferred_inc,st_bonds_payable,payable_to_reinsurer,rsrv_insur_cont,acting_trading_sec,acting_uw_sec,non_cur_liab_due_1y,oth_cur_liab,total_cur_liab,bond_payable,lt_payable,specific_payables,estimated_liab,defer_tax_liab,defer_inc_non_cur_liab,oth_ncl,total_ncl,depos_oth_bfi,deriv_liab,depos,agency_bus_liab,oth_liab,prem_receiv_adva,depos_received,ph_invest,reser_une_prem,reser_outstd_claims,reser_lins_liab,reser_lthins_liab,indept_acc_liab,pledge_borr,indem_payable,policy_div_payable,total_liab,treasury_share,ordin_risk_reser,forex_differ,invest_loss_unconf,minority_int,total_hldr_eqy_exc_min_int,total_hldr_eqy_inc_min_int,total_liab_hldr_eqy,lt_payroll_payable,oth_comp_income,oth_eqt_tools,oth_eqt_tools_p_shr,lending_funds,acc_receivable,st_fin_payable,payables,hfs_assets,hfs_sales,cost_fin_assets,fair_value_fin_assets,cip_total,oth_pay_total,long_pay_total,debt_invest,oth_debt_invest,oth_eq_invest,oth_illiq_fin_assets,oth_eq_ppbond,receiv_financing,use_right_assets,lease_liab,contract_assets,contract_liab,accounts_receiv_bill,accounts_pay,oth_rcv_total,fix_assets_total,update_flag` |
| `cashflow` | `ts_code,ann_date,f_ann_date,end_date,comp_type,report_type,end_type,net_profit,finan_exp,c_fr_sale_sg,recp_tax_rends,n_depos_incr_fi,n_incr_loans_cb,n_inc_borr_oth_fi,prem_fr_orig_contr,n_incr_insured_dep,n_reinsur_prem,n_incr_disp_tfa,ifc_cash_incr,n_incr_disp_faas,n_incr_loans_oth_bank,n_cap_incr_repur,c_fr_oth_operate_a,c_inf_fr_operate_a,c_paid_goods_s,c_paid_to_for_empl,c_paid_for_taxes,n_incr_clt_loan_adv,n_incr_dep_cbob,c_pay_claims_orig_inco,pay_handling_chrg,pay_comm_insur_plcy,oth_cash_pay_oper_act,st_cash_out_act,n_cashflow_act,oth_recp_ral_inv_act,c_disp_withdrwl_invest,c_recp_return_invest,n_recp_disp_fiolta,n_recp_disp_sobu,stot_inflows_inv_act,c_pay_acq_const_fiolta,c_paid_invest,n_disp_subs_oth_biz,oth_pay_ral_inv_act,n_incr_pledge_loan,stot_out_inv_act,n_cashflow_inv_act,c_recp_borrow,proc_issue_bonds,oth_cash_recp_ral_fnc_act,stot_cash_in_fnc_act,free_cashflow,c_prepay_amt_borr,c_pay_dist_dpcp_int_exp,incl_dvd_profit_paid_sc_ms,oth_cashpay_ral_fnc_act,stot_cashout_fnc_act,n_cash_flows_fnc_act,eff_fx_flu_cash,n_incr_cash_cash_equ,c_cash_equ_beg_period,c_cash_equ_end_period,c_recp_cap_contrib,incl_cash_rec_saims,uncon_invest_loss,prov_depr_assets,depr_fa_coga_dpba,amort_intang_assets,lt_amort_deferred_exp,decr_deferred_exp,incr_acc_exp,loss_disp_fiolta,loss_scr_fa,loss_fv_chg,invest_loss,decr_def_inc_tax_assets,incr_def_inc_tax_liab,decr_inventories,decr_oper_payable,incr_oper_payable,others,im_net_cashflow_oper_act,conv_debt_into_cap,conv_copbonds_due_within_1y,fa_fnc_leases,im_n_incr_cash_equ,net_dism_capital_add,net_cash_rece_sec,credit_impa_loss,use_right_asset_dep,oth_loss_asset,end_bal_cash,beg_bal_cash,end_bal_cash_equ,beg_bal_cash_equ,update_flag` |
| `forecast` | `ts_code,ann_date,end_date,type,p_change_min,p_change_max,net_profit_min,net_profit_max,last_parent_net,summary,change_reason` |
| `express` | `ts_code,ann_date,end_date,revenue,operate_profit,total_profit,n_income,total_assets,total_hldr_eqy_exc_min_int,diluted_eps,diluted_roe,yoy_net_profit,bps,perf_summary,update_flag` |
| `index_daily` | `ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount` |
| `index_basic` | `ts_code,name,fullname,market,publisher,index_type,category,base_date,base_point,list_date,weight_rule` |
| `moneyflow` | `ts_code,trade_date,buy_sm_amount,sell_sm_amount,buy_sm_vol,sell_sm_vol,buy_md_amount,sell_md_amount,buy_md_vol,sell_md_vol,buy_lg_amount,sell_lg_amount,buy_lg_vol,sell_lg_vol,buy_elg_amount,sell_elg_amount,buy_elg_vol,sell_elg_vol,net_mf_amount,net_mf_vol` |
| `hk_hold` | `trade_date,code,name,vol,ratio,ts_code,exchange` |
| `top_list` | `trade_date,ts_code,name,close,pct_change,turnover_rate,amount,l_sell,l_buy,l_amount,net_amount,net_rate,amount_rate,float_values,reason` |
| `top_inst` | `trade_date,ts_code,exalter,side,buy,buy_rate,sell,sell_rate,net_buy` |
| `block_trade` | `ts_code,trade_date,price,vol,amount,buyer,seller` |
| `margin` | `exchange_id,trade_date,rzye,rzmre,rzche,rqye,rqmcl,rzrqye` |
| `margin_detail` | `trade_date,ts_code,name,rzye,rqye,rzmre,rzche,rqmcl,rzrqye` |
| `stk_holdertrade` | `ts_code,ann_date,holder_name,holder_type,in_de,change_vol,change_ratio,after_share,after_ratio,avg_price,total_share,begin_date,close_date` |
| `stk_holdernumber` | `ts_code,ann_date,end_date,holder_num` |
| `stk_weekly_monthly` | `ts_code,trade_date,end_date,freq,open,high,low,close,pre_close,vol,amount,change,pct_chg` |
| `share_float` | `ts_code,ann_date,float_date,float_share,float_ratio,holder_name,share_type` |
| `pledge_stat` | `ts_code,end_date,pledge_count,unrest_pledge,rest_pledge,total_share,pledge_ratio` |
| `pledge_detail` | `ts_code,ann_date,holder_name,pledge_amount,start_date,end_date,is_release,release_date,pledgor,holding_amount,pledged_amount,p_total_ratio,h_total_ratio,is_buyback` |
| `repurchase` | `ts_code,ann_date,end_date,proc,exp_date,vol,amount,high_limit,low_limit` |
| `top10_holders` | `ts_code,ann_date,end_date,holder_name,hold_amount,hold_ratio,hold_float_ratio,hold_change,holder_type` |
| `top10_floatholders` | `ts_code,ann_date,end_date,holder_name,hold_amount,hold_ratio,hold_float_ratio,hold_change,holder_type` |
| `report_rc` | `ts_code,name,report_date,report_title,report_type,classify,org_name,author_name,quarter,op_rt,op_pr,tp,np,eps,pe,rd,roe,ev_ebitda,rating,max_price,min_price,imp_dg,create_time` |
| `fina_audit` | `ts_code,ann_date,end_date,audit_result,audit_fees,audit_agency,audit_sign` |
| `fina_mainbz` | `ts_code,end_date,bz_item,bz_code,bz_sales,bz_profit,bz_cost,curr_type,update_flag` |
| `disclosure_date` | `ts_code,ann_date,end_date,pre_date,actual_date,modify_date` |

---

## 附录 B：限流参考值

> 下表为 Tushare 官方限流参考值（次/分钟）。项目实际配置见 `src/main/resources/ratelimit.yml`，已在官方值上留 **10%~25% 余量**：`daily` 500→400、`adj_factor` 200→160、`share_float`/`pledge_stat`/`pledge_detail`/`repurchase` 60→48、`report_rc` 1→1、`fina_indicator` 150（原未配置，已补）；其余按同比例折算，全部可在配置文件调整、重启生效。

| api_name | 次/分钟 | api_name | 次/分钟 |
|---|---|---|---|
| `daily` | 500 | `top_list` | 180 |
| `stock_basic` | 500 | `top_inst` | 180 |
| `trade_cal` | 500 | `moneyflow` | 180 |
| `index_basic` | 500 | `daily_basic` | 180 |
| `index_daily` | 500 | `block_trade` | 180 |
| `adj_factor` | 200 | `hk_hold` | 180 |
| `index_weight` | 200 | `margin` | 180 |
| `index_classify` | 200 | `margin_detail` | 180 |
| `index_member_all` | 200 | `stk_holdertrade` | 180 |
| `namechange` | 200 | `stk_holdernumber` | 180 |
| `suspend_d` | 200 | `fina_audit` | 150 |
| `income` | 200 | `fina_mainbz` | 150 |
| `balancesheet` | 200 | `top10_holders` | 120 |
| `cashflow` | 200 | `top10_floatholders` | 120 |
| `forecast` | 200 | `share_float` | 60 |
| `express` | 190 | `pledge_stat` | 60 |
| `stk_limit` | 180 | `pledge_detail` | 60 |
| `dividend` | 180 | `repurchase` | 60 |
| `stk_weekly_monthly` | 200 | `report_rc` | **1** |
| `disclosure_date` | 300 | `fina_indicator` | 150 |

---

## 附录 C：表与幂等键速查

| 表 | 主键 / 唯一键 |
|---|---|
| `daily` | `(ts_code, trade_date)` |
| `stock_basic` | `ts_code` |
| `trade_cal` | `(exchange, cal_date)` |
| `adj_factor` | `(ts_code, trade_date)` |
| `dividend` | `(ts_code, end_date, ann_date)` |
| `daily_basic` | `(trade_date, ts_code)` |
| `fina_indicator` | `(ts_code, end_date)` |
| `index_weight` | `(index_code, trade_date, con_code)` |
| `sw_industry` | `(index_code, src)` |
| `sw_industry_member` | 幂等键不适用（全量替换，按 L1 行业代码循环拉取） |
| `stock_namechange` | `(ts_code, start_date)` |
| `stock_suspend_d` | `(ts_code, trade_date, suspend_type)` |
| `stock_stk_limit` | `(ts_code, trade_date)` |
| `income` | `(ts_code, end_date, report_type)` |
| `balancesheet` | `(ts_code, end_date, report_type)` |
| `cashflow` | `(ts_code, end_date, report_type)` |
| `forecast` | `(ts_code, end_date, ann_date)` |
| `express` | `(ts_code, end_date)` |
| `index_daily` | `(ts_code, trade_date)` |
| `index_basic` | `ts_code` |
| `stock_moneyflow` | `(ts_code, trade_date)` |
| `hk_hold` | `(trade_date, code)` |
| `top_list` | 无主键，按 `(trade_date, ts_code, name, reason)` 删除 |
| `top_inst` | `(trade_date, ts_code, exalter, side)` |
| `block_trade` | `(trade_date, ts_code, buyer, seller)` |
| `margin` | `(exchange_id, trade_date)` |
| `margin_detail` | `(trade_date, ts_code)` |
| `stk_holdertrade` | `(ts_code, ann_date, holder_name, in_de, begin_date, close_date)` |
| `stk_holdernumber` | `(ts_code, end_date)` |
| `stock_weekly_quote` | `(ts_code, trade_date)` |
| `stock_monthly_quote` | `(ts_code, trade_date)` |
| `share_float` | `(ts_code, float_date, holder_name)` |
| `pledge_stat` | `(ts_code, end_date)` |
| `pledge_detail` | 按 `(ts_code, ann_date)` 整公告删除后插入 |
| `repurchase` | 按 `(ts_code, ann_date)` 整公告删除后插入 |
| `top10_holders` | 按 `(ts_code, end_date)` 整期删除后插入 |
| `top10_floatholders` | 按 `(ts_code, end_date)` 整期删除后插入 |
| `report_rc` | 按 `(ts_code, report_date, org_name)` 删除后插入 |
| `fina_audit` | `(ts_code, end_date, ann_date)` |
| `fina_mainbz` | `(ts_code, end_date, bz_item, bz_code)` |
| `disclosure_date` | `(ts_code, end_date)` |

> 说明：上表 41 张表中 **34 张建唯一键**；`top_list`、`sw_industry_member`、`pledge_detail`、`repurchase`、`top10_holders`、`top10_floatholders`、`report_rc` 共 **7 张不建唯一约束**，按「业务删除键整块先删后插」或「全量替换」保证幂等（删除键见上表对应行）。幂等键列必须非空，缺失/为空的行写入时丢弃并记 warn 日志。
