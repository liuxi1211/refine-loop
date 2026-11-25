# RefineLoop 数据中心前端（Step 7 最小骨架）

Vue 3 + Vite + TypeScript + Pinia + Vue Router（hash 模式）。本批次只实现数据中心两个视图：
**数据总览**（41 表清单 + 表详情抽屉）与 **接入作业**（编排进度 / 作业历史 / 数据质量门 / 初始回填向导）。
按批次范围约束：**不引入 Element Plus、不做登录/JWT、图表不引第三方库**（柱图/进度条为纯 CSS）。

## 启动

```bash
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173，/tables /ingest /quality /stats /api 代理到 127.0.0.1:8080
npm run build      # vue-tsc 类型检查 + vite 纯静态产物（dist/）
npm run preview    # 预览构建产物（同样带代理）
```

> 视觉唯一事实源：`docs/原型图/数据中心-交互原型.html`，`src/styles/` 中的 CSS 变量与组件类直接取自原型。

## 目录

```
src/
  api/          # fetch 统一封装（解包 Result{code,msg,data}、错误 toast、后端离线错误态）+ 各模块端点
  stores/       # Pinia：ui（toast/确认弹窗/后端连通性）、tables、stats、ingest、quality
  components/   # 侧边栏/顶栏/统计卡/状态 chip/抽屉/预览/柱图/编排卡/质量门/回填向导/通用弹窗
  views/        # DataOverview.vue / IngestJobs.vue
  styles/       # tokens（原型 CSS 变量）+ base（布局）+ components（通用组件类）
  router/       # hash 模式：/overview（默认）、/jobs
  utils/        # 千分位/yyyyMMdd/耗时/状态映射
```

## 依赖的运维 API（见 .trae/documents/t2-t5-dev-contract.md §9）

| 页面/交互 | 端点 |
|---|---|
| 总览统计卡 / 表清单 | `GET /tables`、`GET /stats/today` |
| 抽屉详情 | `GET /tables/{table}`、`GET /tables/{table}/jobs` |
| 数据预览 | `GET /tables/{table}/preview?code=&start=&end=&columnMode=key\|all&page=&size=&order=` |
| 每日增量开关 | `POST /tables/{table}/daily-toggle` `{enabled}` |
| 单表增量 / 全量回填 | `POST /tables/{table}/run`、`POST /tables/{table}/backfill` `{start,end}` |
| 运行中编排（5s 轮询） | `GET /ingest/orchestrations/current` |
| 一键每日增量 | `POST /ingest/daily` |
| 作业历史 / 失败重试 | `GET /ingest/jobs?status=&table=&page=&size=`、`POST /ingest/jobs/{id}/retry` |
| 初始回填向导 | `POST /ingest/backfill-plan` `{mode:standard\|all\|custom, tables}` |
| 质量门 | `GET /quality/latest`、`POST /quality/run`、`GET /quality/history?days=30&page=&size=` |

## 后端未启动时的表现

所有请求经 `src/api/http.ts`：网络失败/超时/Vite 代理 500/非 `Result` 响应统一抛 `ApiError`，
toast 提示「后端未启动或接口不可用」，页面渲染带「重新加载」按钮的错误态（不白屏），
侧边栏显示后端接口离线状态；质量门/编排卡等局部区块各自错误态，互不拖垮整页。

## 已知降级（相对原型）

- 原型「暂停编排」「执行方式」「历史起点/分晚安排」等控件在 Step 6 API 契约中没有对应端点/参数，未实现；
- 原型「今日 Tushare 调用较昨日 +12% / 峰值分钟」无统计数据源，改为展示 `rate_limit_counter` 今日汇总口径说明；
- 数据预览「导出」实现为前端导出当前页 CSV（原型为模拟提示），不新增后端端点；
- 顶栏环境标识按本批使用 `DEV`（原型为 `PROD`）。
