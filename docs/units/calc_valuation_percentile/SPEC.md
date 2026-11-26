---
unit_id: calc_valuation_percentile
version: v1.0
type: compute
scope: BATCH
category: 基本面分析类
pipeline: P1 / S1（初筛信号）与 S3（全链路分析）复用同一事实表
status: implemented
since: 2026-11-26
---

# calc_valuation_percentile · 能力单元 Spec（v1.0）

> 上游：《核心能力体系设计详案》第 5 章 Spec A（5.1~5.8）；框架范式：《技术架构与模块设计》4.2.3。
> 本文只补「代码怎么落」与「文档没写死、实现必须选一个」的口径；口径变更 = 单元升版（详案 9.1）。

## 单元定义

| 项 | 值 |
|---|---|
| unit_id | `calc_valuation_percentile` |
| 名称 | 估值分位计算 |
| 类别 | 基本面分析类（通用层，对流派不敏感） |
| 类型 | `compute`（仅 L1，无 LLM） |
| 版本 | `v1.0`（事实 Schema 同步 `v1.0`） |
| 粒度 | `BATCH`：一次执行处理整个标的池 |
| 所属流水线 | P1 S1（初筛）与 S3（分析）共用；产出落 `unit_fact`，S3 按 ts_code 装配 FactBundle |
| 实现 | `capability/units/compute/CalcValuationPercentileUnit` + `EmpiricalPercentile` + `ValuationFactBuilder` + `ValuationIndustryBaseline` |

## ① 方法论来源

- 参考 skill：`valuation_snapshot`（提炼 Step 1 口径选择、Step 4 预期解读、5 条质量要求中的「先选口径再谈高低」与「结论必须回到经营与预期」）；
- 流派：成长 + 趋势（本单元属通用层，不随流派切换）；
- 明确不采纳：skill 的「交互式确认当前口径」环节——无人值守流水线无法交互，口径适配交由下游 `interpret_valuation` 在 prompt 中判断。

本单元**只产事实、不下结论**：阈值分档（`p25 以下=低位`）作为 `band_3y` 供下游引用，不在事实里写「低估/高估」（详案 5.4 规则 3）。

## ② 输入数据契约

| 项 | 值 |
|---|---|
| 主来源表 | `daily_basic`（手册接口卡：180 次/分钟，幂等键 `(trade_date, ts_code)`） |
| 使用字段 | `close, pe_ttm, pb, ps_ttm, dv_ttm, total_mv` |
| 辅助表 | `sw_industry_member`（申万一级归属；全量替换表，按 `out_date` 判定当期归属） |
| 历史窗 | `[tradeDate 往前 3 个自然年, tradeDate]`，约 750 个交易日 |
| 取数方式 | 按池分页（`PAGE_SIZE=50`），每页一次 `ts_code IN (...)` + 日期区间查询，按 `(ts_code, trade_date)` 升序 |
| 索引依赖 | `idx_daily_basic_ts_code (ts_code, trade_date)`——唯一键 `(trade_date, ts_code)` 的前缀支撑不了按标的回查历史 |
| 缺失处理 | 当日 `daily_basic` 缺行 → `data_status=missing`，**禁止沿用昨日值**；字段为空 → 该指标不输出（不填 0） |
| 禁止 | 远端取数（数据就绪是 S0 数据门的职责）、`LocalDate.now()`（时间一律取 `FactContext.tradeDate()`）、写库 |

## ③ 计算规范

### 样本口径

| 指标 | 参与分位的样本 | 说明 |
|---|---|---|
| `pe_ttm` | 窗口内 `pe_ttm > 0` 的交易日 | `≤0` 为亏损样本，单列进 `loss_ratio_3y`，**不剔除、不参与分位**（详案 5.3） |
| `pb` / `ps_ttm` | `> 0` 的交易日 | 负值属数据异常，排除并计入 notes 口径说明 |
| `dv_ttm` | `≥ 0` 的交易日 | 0 = 不分红，是合法取值，必须计入分布 |

`loss_ratio_3y = 非正 pe_ttm 样本数 / 非空 pe_ttm 样本数`（保留 4 位）。

### 公式

1. **分位（经验 CDF）**：`percentile = (样本中 ≤ 当日值的个数) / 样本总数 × 100`，保留 2 位；含当日样本，因此样本最大值分位为 100.00。不使用正态假设（A 股 PE 右偏长尾，z-score 会把极端值算成高概率）。
2. **分位区间（nearest-rank）**：第 p 百分位 = 升序样本第 `ceil(p × n / 100)` 个（1-based），输出 `p10/p25/p50/p75/p90`，保留 4 位，**不插值**（插值会产出样本中不存在的 PE 值，破坏「数字可溯源」）。
3. **同业相对位置**：`vs_industry = (个股 pe_ttm / 行业当期 pe_ttm 中位数) - 1`，保留 4 位；行业中位数取「申万一级、同一交易日、`pe_ttm > 0` 的正样本中位数」（负 PE 混入会凭空压低行业估值）。
4. **市值口径**：`total_mv_yi = total_mv(万元) / 10000`，保留 2 位。

### 阈值分档（仅供下游，不在本单元判定）

`p25 以下 = 低位`、`p25~p75 = 中位`、`p75 以上 = 高位`；下游引用 `band_3y.pe_ttm.p25/p75` 自行分档。

### 输出事实表 Schema（`valuation_fact` v1.0）

数值一律是**指标叶子** `{"value": …, "unit": …}`——这是 L3 校验「指标名 + 数值 + 单位」三重比对的前提（详案 3.3 契约 9），因此与详案 5.4 示例的裸数字不同（偏差记录见 ⑦）。

```json
{
  "ts_code": "000001.SZ",
  "trade_date": "20261122",
  "data_status": "ok",
  "current": {
    "pe_ttm": {"value": 5.2, "unit": "倍"},
    "pb": {"value": 0.58, "unit": "倍"},
    "ps_ttm": {"value": 1.1, "unit": "倍"},
    "dv_ttm": {"value": 4.8, "unit": "pct"},
    "total_mv_yi": {"value": 3120.00, "unit": "亿元"}
  },
  "percentile_3y": {
    "pe_ttm": {"value": 18.50, "unit": "pct"},
    "pb": {"value": 22.10, "unit": "pct"},
    "ps_ttm": {"value": 35.00, "unit": "pct"},
    "method": "empirical_cdf"
  },
  "band_3y": {
    "pe_ttm": {
      "p10": {"value": 4.1, "unit": "倍"},
      "p25": {"value": 4.8, "unit": "倍"},
      "p50": {"value": 5.6, "unit": "倍"},
      "p75": {"value": 6.9, "unit": "倍"},
      "p90": {"value": 8.2, "unit": "倍"}
    }
  },
  "loss_ratio_3y": {"value": 0.02, "unit": "ratio"},
  "relative_industry": {
    "industry_code": "801780",
    "pe_ttm_median": {"value": 6.1, "unit": "倍"},
    "vs_industry": {"value": -0.15, "unit": "ratio"}
  },
  "window": {
    "start_trade_date": "20231122",
    "end_trade_date": "20261122",
    "sample_days": {"value": 731, "unit": "交易日"}
  },
  "notes": ["分位基于 pe_ttm 盈利样本计算，亏损样本占比见 loss_ratio_3y"]
}
```

**指标路径（下游引用名，`FactMetrics` 摊平口径）**：`current.pe_ttm`、`current.pb`、`current.ps_ttm`、
`current.dv_ttm`、`current.total_mv_yi`、`percentile_3y.pe_ttm|pb|ps_ttm`、`band_3y.pe_ttm.p10|p25|p50|p75|p90`、
`loss_ratio_3y`、`relative_industry.pe_ttm_median`、`relative_industry.vs_industry`、`window.sample_days`。
常量集中在 `ValuationFactSchema`，下游与测试只引用常量，不硬编码字符串。

**省略规则**（`FactResult.payload` 不允许 null 值）：任一指标缺失时整块省略，块内缺字段也省略；
`percentile_3y` 无任何分位时不输出（连同 `method` 一起省略，避免空壳块被误读）。

## ④ 解读规范

不适用（compute 单元不产叙事）。下游消费方是 `interpret_valuation`（T14）与 `interpret_bull_bear_collision`（T15）。

## ⑤ 结构化输出契约

- 事实落 `unit_fact`：`unit_id=calc_valuation_percentile`、`unit_version=v1.0`、`data_status` 列取
  `COMPLETE/DEGRADED/MISSING`（`payload.data_status` 取 `ok/degraded/missing`，两者单向映射）；
- 下游 `facts_used` 只能引用上面的指标路径，且必须携带同口径 `unit`；
- 本单元不产判断块（`judgment` 由 interpret 单元 + 存档器负责）。

## ⑥ 质量要求

1. **先选对口径，再谈高低**（skill 原文）：事实表必须同时给出 `current` 与 `percentile_3y`，不得只给分位；
2. **结论必须回到经营与预期**：本单元不产结论，但必须把「分位窗口边界」写进 `window`，让下游能讲清口径（详案 5.5 禁止脱离窗口边界谈分位）；
3. **数字可溯源**：每个数值都是指标叶子且带单位；`notes` 只解释口径，**不写数字**（notes 进 prompt 后若带数字，模型抄进正文会被正文数字扫描判失败）；
4. **确定性**：同输入必同输出；页大小不影响结果（分页只影响内存，不影响口径）。

## ⑦ 失效与降级

| 场景 | data_status | 输出 | 下游行为 |
|---|---|---|---|
| 当日 `daily_basic` 缺行 | `MISSING` | 仅 `ts_code/trade_date/data_status/notes` | interpret 标注「数据异常」，confidence 最高「低」 |
| 窗口内交易日 < 480（不足两年） | `DEGRADED` | 当前值 + `loss_ratio_3y` + `window`，**无分位与区间** | 结论必须降级并说明窗口不足 |
| `pe_ttm` 样本全为非正（全亏损） | `DEGRADED` | 无 `pe_ttm` 分位；`loss_ratio_3y=1`；PB/PS 分位可保留 | 改用 PS/PB 主口径（详案 5.7） |
| 可算分位的指标全部缺失 | `DEGRADED` | 仅当前值 | 同「仅事实存档」 |
| 行业归属缺失 / 行业无正样本 | `COMPLETE`（附 note） | 省略 `relative_industry` | 报告标注「无同业参照」 |

降级不是异常：任何数据不足都返回事实 + 原因，不抛业务异常（`ApiException` 只用于「调用方传了非法 tradeDate/空 ctx」这类编程错误）。

**与文档的三处偏差（按「更严/更可测」处理，均已在本文件记录）**：

| # | 偏差 | 理由 |
|---|---|---|
| D1 | 数值写成 `{value, unit}` 叶子而非裸数字 | T8 的 `FactMetrics`/`FactsUsageValidator` 只认叶子；裸数字无法参与「名+值+单位」硬比对，T11 的 L3 必过要求 |
| D2 | 新增 `window{start_trade_date,end_trade_date,sample_days}` | 详案 5.5 要求「分位窗口不足必须降级结论」，窗口边界必须是可引用事实；不改 5.4 任何公式 |
| D3 | 窗口起点用「自然年减法」而非「往前数 750 个交易日」 | 后者要读交易日历，会把「日历未回填」变成单元失败；实际覆盖由 `window.sample_days` 如实记录 |

## ⑧ 测试要求

`src/test/java/.../capability/units/compute/CalcValuationPercentileUnitTest`（真实 SQLite + 真实 Mapper）：

| 用例 | 断言要点 |
|---|---|
| 正常序列（500 样本，当日 pe=15） | 分位 50.40；`p10/p25/p50/p75/p90 = 10.98/12.48/14.98/17.46/18.96`；`loss_ratio=0`；`total_mv_yi=100`；`vs_industry=-0.25`；`status=COMPLETE` |
| 不足两年（100 样本） | `DEGRADED`；有 `current`、无 `percentile_3y`/`band_3y`；note 含「不足两年」 |
| 全亏损（500 样本 pe=-2.5） | `DEGRADED`；`loss_ratio=1`；无 pe 分位、PB 分位仍在；note 含「全亏损」 |
| 当日缺行 | `MISSING`；不输出 `current`；池内每个标的仍各一行 |
| 行业归属缺失 | 无 `relative_industry` + note；分位照常 |
| 分页（pageSize=2，池 5 只） | 5 行、不重不漏、不跨页串数据 |
| 空池 / 窗口起点 / payload 无 null | 空结果、`20261126→20231126`、`20240229→20210228`、缺失字段整块省略 |

`EmpiricalPercentileTest` 覆盖 CDF 的并列样本、缺失语义与 nearest-rank 的边界（单样本、空样本、非法分位点）。
