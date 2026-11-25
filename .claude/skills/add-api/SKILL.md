---
name: add-api
description: 在 refine-loop 新增或修改后端接口（/api/**）时使用。给出「入参/出参 record → Service 用例 → Controller → 错误码 → 单测 → 前端类型与 04 §9 端点清单」的完整同步清单，避免只改后端导致契约漂移。
---

# 新增 / 修改一个接口

契约规范：`.claude/rules/04-接口契约规范.md`（URL §1、`Result` §3、错误码 §3.3、鉴权 §8、端点清单 §9）。
下面 8 步按顺序做，任一步缺失都算未完成。

1. **先定契约**：路径（`/api/...`，kebab、复数、动词白名单）、HTTP 方法、入参字段、出参字段、失败语义（400 / 404 / 409 / 业务码）。
2. **入参**：`web/<域>/<域>Requests.java` 加 `record XxxRequest(...)`，`@Valid` + 校验注解；枚举字段用 `@EnumCode(XxxEnum.class)`。
3. **出参**：`<域>/*Views.java` 或新增 `XxxView` record；**不返回 Entity**；非空字段不要返回 null，空集合返回 `[]`。
4. **服务**：`<域>/service/` 加/改用例方法；业务规则用 `Asserts`；写路径 `@Transactional` 只在 service；分页用 `PageQuery` + `PageResults`。
5. **Controller**：`web/<域>/` 只做「解析参数 → 调服务 → `Result.ok(...)`」；每个端点一行 Javadoc；写动作加 `@RequirePermission`；
   补 `@Operation(summary=...)`。
6. **错误码**：先复用 `CommonErrorCode`（400/404/409/1003/1004/500）；确需分支才按段新增（`tushare` 2001+、`ops` 3001+、`capability` 4001+…），只增不改。
7. **单测**：`src/test/java/.../web/<域>/XxxControllerTest`（MockMvc）覆盖成功 + 400 + 404/409 分支；服务用例补对应单测（真实 SQLite）。
8. **同步前端与文档**：`frontend/src/api/types.ts` + `api/<域>.ts` + 渲染处；在 `.claude/rules/04-接口契约规范.md` §9 加一行。

## 自检

```powershell
mvn -B test                                  # ArchitectureRulesTest 会拦 web 直连 data / 非 Result 返回
cd frontend; npm run build                   # 改了前端必跑
```

报告时要说明：端点路径、失败语义、改了哪些前端文件、端点清单是否已更新。
