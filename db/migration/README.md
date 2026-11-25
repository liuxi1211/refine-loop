# 数据库迁移脚本目录（1.0 之后启用）

> 规范依据：[开发规范 02-数据库与SQL规范](../../.claude/rules/02-数据库与SQL规范.md) §12。
> **当前阶段（1.0 之前）不在这里写脚本**：`db/schema.sql` 仍是唯一事实源，改结构 = 改脚本 + 删本地库重建。

## 为什么现在建这个目录

`db/schema.sql` 全部是 `CREATE TABLE IF NOT EXISTS`，对**已存在**的库不会修改表结构——
一旦开始写真实数据（回填完成、判断开始存档），"改脚本 + 重启"就会出现「代码新了、库还是旧结构」的静默漂移。
所以进入 1.0（第一次持久化真实数据）之前，必须把迁移机制补上；目录与命名先定死，避免临时拍脑袋。

## 命名与执行规则

```
db/migration/V0001__baseline_58_tables.sql     ← 1.0 基线（把当时的 schema.sql 冻结一份）
db/migration/V0002__add_judgment_state.sql     ← 之后每次结构变更一个文件
```

1. 文件名 `V<4 位序号>__<小写下划线描述>.sql`，序号连续、不跳号、不复用；
2. **只增不改**：已发布的脚本永远不编辑（改了等于不同环境跑了不同 DDL）；
3. 每个脚本必须是**幂等**的，或由 `schema_version` 表保证只执行一次；
4. 执行顺序 = 序号升序；任一脚本失败 → 应用启动失败（fail-fast），不允许半迁移；
5. 破坏性变更（删列/改类型/改码）必须：备份库 → 迁移脚本 → 应用兼容读 → 单独发布；
6. PR 必须附：影响行数估算、回滚方案、备份命令。

## 必需的元数据表（与迁移器一同落地）

```sql
CREATE TABLE IF NOT EXISTS schema_version (
  version    VARCHAR(32) NOT NULL PRIMARY KEY,   -- V0002
  applied_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  checksum   VARCHAR(64) NULL                    -- 防止脚本被改动
);
```

## 落地清单（P1-17 / 附录）

- [ ] `SchemaMigrator`：启动期先跑 `db/schema.sql`（幂等补表），再按序执行未应用的 `db/migration/V*.sql`；
- [ ] `schema_version` 记录 + 校验和比对（脚本被改动即启动失败）；
- [ ] 单测：空库迁移、重复启动幂等、坏脚本 fail-fast；
- [ ] 运维手册补「发布前备份 + 迁移 + 回滚」步骤（见开发规范 07 §6）。
