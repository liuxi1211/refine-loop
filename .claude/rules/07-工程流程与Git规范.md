---
paths:
  - "docs/**"
  - "*.md"
  - "pom.xml"
---

> 本文件于 2026-11-25 从旧规范目录迁入 `.claude/rules/`，此后**此处为唯一载体**（规范正文只此一份，`docs/` 只放业务与设计文档）。

# 07 工程流程与 Git 规范

> 目标：让"半年后的自己"能回答「这行为什么这么改」「哪一版能回滚」「库文件怎么恢复」。
> 强制等级：可机械判定的为 `[L1]`（测试 / 构建）；协作纪律为 `[L3]`。

---

## 1. 版本控制基线

| 项 | 约定 |
|---|---|
| 仓库 | 单仓库（后端 + 前端 + 文档 + schema），根目录初始化 Git |
| 主分支 | `main`（唯一长期分支） |
| 工作分支 | `feat/<短横线主题>`、`fix/<主题>`、`docs/<主题>`、`chore/<主题>`、`refactor/<主题>`；生命周期 ≤ 3 天 |
| 合并方式 | 单人项目：本地门禁通过后直推 `main`；有多人/多会话并行时用 PR + 自审清单 |
| 标签 | 可发布版本打 `v<major>.<minor>.<patch>`（当前 `0.x`） |
| 大文件 | 不入库（`target/`、`node_modules/`、`*.db`、`*.jar`、`frontend/dist/`、参考材料目录） |

### 1.1 首次初始化（若仓库尚无 `.git`）

```bash
git init -b main
git add -A
git commit -m "chore: 初始化仓库并纳入工程规范"
```

`.gitattributes` 负责换行（跨 Windows/Linux 一致性）：

```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.png binary
*.jpg binary
*.jar binary
*.db binary
```

`.gitignore` 必须覆盖：`target/`、`logs/`、`*.log`、`data/`、`.idea/`、`*.iml`、`.vscode/`、`node_modules/`、`dist/`、`application-local.yml`、`.env*`、`docs/旧项目参考-*/`、`docs/开源金融类ai插件参考/`、`*.db`、`*.db-wal`、`*.db-shm`、`db/backup/`。

---

## 2. 提交信息（Conventional Commits）

```
<type>(<scope>): <一句话说明>

<为什么这么改（动机/约束/取舍）>

<影响面：表/接口/配置/前端页面>

Refs: <任务或 issue>
```

| type | 用途 |
|---|---|
| `feat` | 新功能/新端点/新表 |
| `fix` | 缺陷修复（必须带回归用例） |
| `refactor` | 结构调整，行为不变 |
| `perf` | 性能 |
| `docs` | 文档、注释 |
| `test` | 测试 |
| `build` | 构建/依赖/版本 |
| `chore` | 杂务（格式化、忽略项） |
| `revert` | 回滚 |

`scope` 词表（与包结构对齐）：`common` `config` `data` `ops` `tushare` `web` `schema` `frontend` `docs` `build`。

示例：

```
feat(ops): 新增质量门历史分页端点

前端时间线需要按天回溯，新增 GET /api/ops/quality/history 支持 days/page/size。

影响面：web/ops/QualityController、ops/QualityOpsService、frontend/src/api/quality.ts
Refs: T5-质量门
```

**禁止**：`update`/`fix bug`/`修改` 这类无信息提交；一个提交里混三件不相关的事；提交产物文件。

---

## 3. 提交粒度

一个提交 = 一个可回滚的意图。**必须同一提交**完成的事：

| 变更 | 同一提交必须包含 |
|---|---|
| 加表/改表 | `db/schema.sql` + 实体 + Mapper + `TushareTableRegistry`（镜像表）+ 单测 + 表注释 |
| 改 API | Controller + 服务 + 错误码 + `web` 单测 + 前端 `api/*.ts` + `types.ts` + 渲染处 + 端点清单（04 §9） |
| 改公共能力 | `common/**` + 使用手册对应章节 + 调用方 + 单测 |
| 改结构/分层 | 代码 + `ArchitectureRulesTest` + 01 结构图 + 附录 |
| 改配置项 | yml + `@ConfigurationProperties` + 默认值说明 + README（若使用者可见） |

**可以拆开**：纯格式化（单独 `chore: 格式化 …`，不夹带逻辑）；依赖升级；文档补写。

---

## 4. 提交前自检

```bash
mvn -B test                    # 后端编译+测试（含架构 / schema 规则）
cd frontend && npm run build   # 前端类型检查+构建（改了 frontend/ 才需要）
```

不设脚本封装，由提交者自觉执行。手工检查：

- [ ] 没有 `System.out`/`console.log`/调试断点/TODO 临时代码；
- [ ] 没有提交密钥、token、本机绝对路径、临时库文件；
- [ ] 新文件已 `git add`（避免"本地能跑、CI 缺文件"）；
- [ ] 提交信息说明了「为什么」，不只是「改了什么」；
- [ ] 文档同步（04 §7 / 总纲 §5）。

---

## 5. 评审（单人项目的最小自审）

多人时走 PR；单人时必须**隔一次上下文**再读一遍（或交给 AI 会话按清单审）。清单见[总纲 §7](00-总纲.md#7-代码评审清单l3逐条打勾)。

评审优先级（时间有限时按此顺序看）：

1. 红线（总纲 §3）：分层越界、SQL 泄漏到 service、Controller 写业务、吞异常；
2. 数据正确性：幂等、事务、时间口径、金额精度；
3. 契约一致性：前后端字段/错误码；
4. 可读性：命名、方法长度、注释。

---

## 6. 版本与发布

| 项 | 约定 |
|---|---|
| 版本号 | `0.<minor>.<patch>`，`minor` 对应里程碑（M1=0.1、M2=0.2…），`patch` 为修复 |
| 构建 | `mvn clean package` → `target/refine-loop-<ver>.jar`；前端 `npm run build` → 部署到 Nginx |
| 发布前 | 备份数据库（`VACUUM INTO`）→ 记录当前 jar 版本 → 执行迁移（若有）→ 启动 → 冒烟（`/api/health`、`/api/ops/tables`） |
| 回滚 | 用上一版 jar 启动 + 若迁移不可逆则恢复库文件；**回滚方案必须在发布前写下** |
| 打 tag | `git tag -a v0.2.0 -m "M1 最小闭环"` |

---

## 7. 数据库变更流程（与 02 §12 呼应）

```
1.0 前：改 db/schema.sql → 删本地库重建 → mvn test
1.0 后：备份 → 写 db/migration/V<seq>__<desc>.sql → 本地在旧库上验证（迁移前后行数/关键查询）
        → 发布 → 观察 → 若失败按 §6 回滚
```

**禁止**：直接 `ALTER` 运行库；改历史迁移脚本；跳过备份；在业务代码里执行 DDL。

---

## 8. 文档同步义务（"代码变了文档没变"= 缺陷）

| 改了什么 | 必须更新 |
|---|---|
| 公共基础设施（响应/错误码/缓存/字典/分页/工具类） | `公共基础设施使用手册.md` |
| 包结构、分层、依赖方向 | 本规范 01 + `ArchitectureRulesTest` |
| 表/字段/索引 | `db/schema.sql` 注释 + 本规范 02（若新增类型/约定）+ 附录 |
| API 端点 | 本规范 04 §9 + `@Operation` + 前端 `types.ts` |
| 技术选型/依赖 | 《技术架构与模块设计》§1 基准清单 |
| 重大设计取舍 | 《技术架构与模块设计》§10 ADR |
| 规范本身 | 总纲 §6 流程 + 附录变更历史 |

---

## 9. 安全与密钥

- 密钥只走环境变量（`TUSHARE_TOKEN`、`DASHSCOPE_API_KEY`、`DB_URL`），`application*.yml` 只允许 `${VAR:}` 占位；
- 日志禁止输出 token/key/完整请求体（`MaskUtils` 脱敏）；
- 依赖安全：每季度 `mvn dependency:tree` + `npm audit` 人工过一遍；新增依赖评审时看维护活跃度；
- 生产库文件权限 `600`，目录 `700`；备份文件同样；
- 对外只暴露 Nginx（80/443），应用端口 8080 只监听回环；
- 鉴权（2026-11-24 已落地）：多用户 + 固定角色 + 不透明令牌（`Authorization: Bearer`），`/api/health` 与 `/api/auth/login` 永久豁免；生产首次启动必须用 `REFINE_LOOP_BOOTSTRAP_PASSWORD` 覆盖默认管理员密码并立即改密。

---

## 10. 运维例行（上线后）

| 频率 | 动作 |
|---|---|
| 每日 | 02:00 备份库文件（保留 14 天）；检查 `/api/ops/stats/today` 与质量门结果 |
| 每周 | 看 `ingest_log` 失败率、`rate_limit_counter` 是否触顶、磁盘占用（WAL 增长） |
| 每月 | `VACUUM`（或重建库）、依赖升级评估、日志归档 |
| 每季度 | 回滚演练（用备份库 + 旧 jar 在本机跑通）、规范复审（哪些规则没被执行） |

---

## 11. 反模式

| 反模式 | 后果 |
|---|---|
| "先提交，回头补测试" | 回归用例永远补不上；bug 复发 |
| 一个提交改 20 个文件、跨 3 个无关主题 | 无法回滚、无法评审 |
| 把 `application-local.yml`、`.env`、`*.db` 提交 | 密钥泄漏、库文件冲突 |
| 手工改生产库结构 | schema 与代码永久漂移 |
| 用 `git commit --amend` 改已推送历史 | 他人/多会话冲突 |
| 打包时忘记前端 | 页面 404（前端产物单独部署，需在发布清单里勾） |
