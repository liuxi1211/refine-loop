---
name: pre-commit
description: 在 refine-loop 提交前使用。跑测试与构建（后端全量测试 + 前端构建）、核对「同一次提交必须一起改」的文档/契约同步、确认不入库产物与偏差登记，并按 Conventional Commits 生成提交信息。
---

# 提交前自检与交付核对

## 1. 跑测试与构建（两条都要绿）

```powershell
mvn -B test                 # 编译 + 全量测试，含 ArchitectureRulesTest / SchemaConventionTest / schema 在真实 SQLite 上执行
cd frontend; npm run build  # 前端类型检查 + 构建（改了 frontend/ 才需要）
```

## 2. 对表检查（同一提交必须一起改）

| 改了什么 | 必须一起改 |
|---|---|
| 表 / 列 | `db/schema.sql` + 实体 + Mapper +（镜像表）注册表 + 单测 + 注释 |
| 接口 | Controller + 服务 + 错误码 + `web` 单测 + `types.ts` + `api/*.ts` + 04 §9 端点清单 |
| 公共能力 | `common/**` + 《公共基础设施使用手册》 + 调用方 + 单测 |
| 结构 / 分层 | 代码 + `ArchitectureRulesTest` + 01 结构图 + 附录 |
| 配置项 | yml + `@ConfigurationProperties` + 默认值说明 + README（使用者可见时） |
| 提交信息 | Conventional Commits，正文写「为什么」与影响面 |

## 3. 不入库

`target/`、`data/*.db*`、`logs/`、`node_modules/`、`frontend/dist/`、`.env*`、密钥、`docs/开源金融类ai插件参考/`、`docs/旧项目参考-*/`；
新文件确认已 `git add`（避免"本地能跑、别人缺文件"）。

## 4. 偏差

违反了某条规范但确定不改 → 登记 `.claude/rules/附录-存量偏差登记.md`（为什么现在不改 + 什么条件下必须改）；十条红线无豁免。

## 5. 完成后报告

跑了哪些命令、结果如何、改了哪些文件、还有什么没做（例如需要 `TUSHARE_TOKEN` 的真实联调）。
