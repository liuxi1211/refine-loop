# T6 + T7 实施计划：能力单元框架与 LLM 调用基座

> 依据：`docs/开发任务计划.md` T6/T7；`docs/技术架构与模块设计.md` §4.2、§6.1、§6.4、§4.2.9；`.claude/rules/01/02/03/04/06`。
> 范围：T6（capability SPI + runtime + FactStore + FactBundle + ArchUnit）与 T7（LLM 调用基座 + 轻量报告模板 + 用量记账）。
> 不在本次范围：T8 校验链/AbstractInterpretUnit、T9 compute 单元、T12 流水线、任何前端改动。

---

## 0. 目标与验收边界

### T6 交付
- `capability/spi`：`CapabilityUnit/ComputeUnit/InterpretUnit`、`@CapabilityUnit` 注解、`FactContext/FactBundle/FactResult/BatchFactResult/InterpretResult` 等。
- `capability/runtime`：`UnitRegistry`（启动扫描 + 版本落 `unit_version`）、`FactStore`（`unit_fact` 读写）。
- ArchUnit：`units → runtime → spi` 依赖方向、runtime 禁止依赖 units、units 之间禁止互调、spi 不依赖 runtime/units。
- 临时 demo compute 单元**只作为测试夹具**验证“注册→执行→落 `unit_fact`”，不留在生产代码。

### T7 交付
- Spring AI 调用基座：业务代码面向 `ChatClient`，但只有 `capability/runtime/llm` 包可直接接触 `ChatClient`。
- `LlmInvoker`：重试/指数退避、429 退避、单次调用硬超时（默认 120s）、token/费用记账写 `llm_usage`、run_id 日志贯穿。
- `ReportGenerator` + `PromptTemplateLoader`：prompt 按 `prompts/{promptId}/{version}.md` 版本化加载，供“今日一句话”等报告类任务使用。
- 多协议/多供应商兼容：一份配置同时支持 **OpenAI 协议**（火山方舟 Coding Plan、DeepSeek、DashScope 兼容模式、任意 OpenAI 兼容自建服务）与 **Anthropic Messages 协议**（Claude 官方、one-api/new-api/LiteLLM 等自建 Anthropic 协议服务），可随时换模型、换协议、换 Base URL。

### 验收边界说明
- 单测（mock ChatClient）覆盖重试/超时/429 路径，可在无 API Key 环境下完成。
- “实调一次 fast 档模型并看到 `llm_usage` 记录”属于**需要用户 Key 的联调验收**，本次实现提供 `LlmSmokeRunner` 开关，由用户提供 Key 后执行；未提供 Key 时该项标记为待联调，不伪造通过。

---

## 1. 现状核对（已确认）

| 事实 | 位置 |
|---|---|
| `capability` 包尚不存在，仅有规划 | [CLAUDE.md](file:///c:/Users/windy/IdeaProjects/refine-loop/CLAUDE.md) §3、[01-工程结构与包结构.md](file:///c:/Users/windy/IdeaProjects/refine-loop/.claude/rules/01-工程结构与包结构.md) §3 |
| `unit_fact` / `unit_version` / `llm_usage` 表已存在 | [schema.sql](file:///c:/Users/windy/IdeaProjects/refine-loop/db/schema.sql) L1055、L1201、L1278 |
| `llm_usage` 缺少 `profile` 列（T7 要求记账含 profile） | 同上 L1278-1291 |
| `pom.xml` 已预留 spring-ai 版本号，但无实际依赖/BOM | [pom.xml](file:///c:/Users/windy/IdeaProjects/refine-loop/pom.xml) L33-35 |
| Spring 装配根、属性类、`Clock`、`TraceContext`、`SchemaTestSupport`、`ArchitectureRulesTest` 均有既有范式 | `config/*`、`common/log/TraceContext`、`src/test/.../support/SchemaTestSupport.java`、`src/test/.../architecture/ArchitectureRulesTest.java` |
| 当前无本机 `~/.m2/repository/org/springframework/ai` 缓存，首次构建需联网下载 | 本地仓库检查结果 |
| Spring AI 1.1.2 使用 `spring-ai-bom` 管理版本；OpenAI 兼容客户端的 completions 路径可配置 | 官方文档（OpenAI Chat 1.1） |
| 火山方舟 Coding Plan 的 OpenAI 兼容 Base URL 是 `https://ark.cn-beijing.volces.com/api/coding/v3`（不是 `/api/v3`） | 火山方舟文档 |
| DeepSeek OpenAI 兼容 Base URL 为 `https://api.deepseek.com`，路径 `/v1/chat/completions` | DeepSeek 文档 |
| Spring AI 1.1.2 有独立的 `spring-ai-anthropic`：`AnthropicApi` 默认 `/v1/messages`、默认版本头 `2023-06-01`，Base URL 可配置 | Spring AI 1.1.2 源码 `AnthropicApi` |

---

## 2. 关键决策（先定，避免执行时再选）

### D1. LLM 接入方式：Spring AI 双协议客户端（OpenAI 兼容 + Anthropic Messages）
- 采用 `spring-ai-bom` + `spring-ai-openai` + `spring-ai-anthropic` + `spring-ai-client-chat`。
- 原因：用户账号与自建服务覆盖两类协议——
  - OpenAI 协议：火山方舟 Coding Plan、DeepSeek、DashScope 兼容模式、任意 OpenAI 兼容服务；
  - Anthropic 协议：Claude 官方、one-api/new-api/LiteLLM 等自建 Anthropic Messages 服务。
- 不采用 DashScope starter：它绑定 DashScope 原生协议，无法一套代码自由切换；DashScope 可用 compatible-mode 走 OpenAI 协议。
- “新版/旧版”差异不新增第三套协议：OpenAI 侧用可配置的 `completions-path` 覆盖新旧路径；Anthropic 侧用可配置的 `anthropic-version` 覆盖版本头。
- 记录为对 T7 原文“spring-ai-alibaba DashScope starter”的显式实施偏差，写入架构文档 §1 与 `docs/开发任务计划.md` T7 实施说明。

### D2. 注解与接口同名冲突
- `capability.spi.CapabilityUnit` 是接口；`@CapabilityUnit` 注解放 `capability.spi.annotation.CapabilityUnit`。
- 接口的 `unitId()/version()/type()` 提供默认实现，直接读取注解，避免注解与接口双源漂移；`UnitRegistry` 启动期校验。

### D3. profile → provider（protocol）→ model 三层配置
- `llm.providers.<name>`：`protocol`（`OPENAI` / `ANTHROPIC`）、`base-url`、`api-key`（只从环境变量）、`completions-path`、`anthropic-version`（仅 Anthropic）。
- `llm.profiles.fast|strong`：引用 provider，声明 `model/temperature/max-tokens/timeout-seconds/价格`。
- 默认：`fast` 走 DeepSeek（OpenAI 协议，`deepseek-chat`），`strong` 走火山方舟（OpenAI 协议，模型名由 `LLM_STRONG_MODEL` 覆盖）；自建 Anthropic 服务只需新增一个 provider 并把 profile 指过去。
- ChatClient **惰性创建**：未配置 Key 不影响应用启动；首次真正调用时抛 `LLM_NOT_CONFIGURED`，避免开发/测试环境被 Key 卡死。

### D4. 用量记账与费用
- `llm_usage` 增加 `model_profile VARCHAR(16) NULL`（位于 `unit_id` 后），记录 fast/strong。
- `cost` 按 profile 配置的 `input-price-per-1m` / `output-price-per-1m` 估算；默认 0（火山方舟 Coding Plan 是订阅制，DeepSeek 单价可自行填），不写死可能过期的价格。
- 每次成功调用写一行；失败调用只记日志，不写 0-token 脏行。

### D5. 重试、429、超时语义
- 最多重试 `max-attempts`（默认 3，即首次 + 2 次重试）。
- 可重试：429/rate limit、5xx、连接异常、IO/超时类瞬时错误；不可重试：401/403/400/404 等 4xx、`NonTransientAiException`。
- 429 使用独立退避（默认 5s 起）；其它瞬时错误指数退避 + 抖动。
- 单次调用硬超时：默认 120s，用虚拟线程执行器 + `Future.get(timeout)` 实现；超时不重试，直接抛 `LLM_TIMEOUT`，避免 2h 总预算被拖穿。
- 同时在 HTTP 客户端设置连接/读取超时，避免 Future 超时后请求长期悬挂。

### D6. 临时 demo compute 单元
- 不新增生产 demo 类；在 `src/test/java/.../capability/support/DemoComputeUnit` 作为测试夹具，验证后随测试长期保留。

### D7. 本次不改的边界
- 不改 T6 SPI 的对外签名以外的既有内容；不实现 T8 的校验链/降级逻辑；不实现 pipeline 编排。
- 不改前端；不改 41 张镜像表；不改 `judgment` 表。

---

## 3. T6 实施清单

### 3.1 `capability/spi`（纯 record/接口/枚举，除注解外无 Spring 依赖）

新增文件：

| 文件 | 内容要点 |
|---|---|
| `capability/package-info.java` | 职责：能力单元框架；依赖：`common/data/config`；禁止依赖 `web/ops/tushare/user/pipeline/judgment/report/version` |
| `capability/spi/package-info.java` | SPI 契约，不依赖 runtime/units/data |
| `capability/spi/annotation/CapabilityUnit.java` | `@Retention(RUNTIME) @Target(TYPE)`；`id()`、`version()`、`type()`、`scope() default PER_STOCK`、`modelProfile() default "fast"` |
| `capability/spi/CapabilityUnit.java` | `unitId()/version()/type()` 默认读注解；`UnitType type()` |
| `capability/spi/UnitType.java` | `COMPUTE / INTERPRET` |
| `capability/spi/UnitScope.java` | `BATCH / PER_STOCK` |
| `capability/spi/ComputeUnit.java` | `computeBatch(FactContext, StockPool)`；`compute(FactContext)` 默认抛 `UnsupportedOperationException`（PER_STOCK 子类反之） |
| `capability/spi/InterpretUnit.java` | `InterpretResult interpret(FactBundle facts)` |
| `capability/spi/FactContext.java` | `record FactContext(String tradeDate, String runId)`；校验 `yyyyMMdd` |
| `capability/spi/StockPool.java` | `record StockPool(String name, List<String> tsCodes)`；去重保序、不可变 |
| `capability/spi/FactDataStatus.java` | `COMPLETE / DEGRADED / MISSING` |
| `capability/spi/FactResult.java` | `record(unitId, unitVersion, schemaVersion, dataStatus, Map<String,Object> payload, List<String> notes)`；compact 构造复制不可变；`<T> T as(Class<T>)` 用 `JsonUtils` 反序列化 |
| `capability/spi/BatchFactResult.java` | `record(List<FactResult> facts)`；不可变、`size()` |
| `capability/spi/FactBundle.java` | `builder(tsCode, tradeDate, runId)` → `ConcurrentHashMap` 并发写入 → `seal()` 后不可变；`fact/find/requireFact/all`；sealed 后写入抛异常；重复 unitId 抛异常（放在 spi 以保证 `InterpretUnit` 不反向依赖 runtime） |
| `capability/spi/InterpretResult.java` | `record(String reportMd, Map<String,Object> judgmentBlock, boolean degraded, List<String> dataGaps)`；只定契约，降级工厂留给 T8 |

### 3.2 `capability/runtime`

| 文件 | 内容要点 |
|---|---|
| `capability/runtime/package-info.java` | 运行时骨架；依赖 spi/common/data/config；禁止依赖 units |
| `capability/runtime/UnitRegistry.java` | 纯类（非 Spring 组件）：`register(Object bean)` 解包 CGLIB、读 `@CapabilityUnit`、校验 id 正则/版本/type-scope、拒绝重复 id；`find/require/all` |
| `capability/runtime/UnitRegistry.java` 内嵌 `Entry` record | `unitId/version/type/scope/modelProfile/instance` |
| `capability/runtime/FactStore.java` | `saveAll(tradeDate, List<FactResult>)` 批量 upsert；`findByUnit`、`findByTradeDateAndTsCode`；payload 走 `JsonUtils` |
| `capability/runtime/UnitVersionStore.java` | 启动期把注册单元写入 `unit_version`（status=ACTIVE、activated_at=clock、interpret 的 prompt_path=`prompts/{id}/{version}.md`） |
| `capability/runtime/CapabilityErrorCode.java` | 4001 DUPLICATE_UNIT、4002 INVALID_UNIT、4003 FACT_INVALID、4004 UNIT_NOT_FOUND |

### 3.3 data 层（唯一 SQL 出入口）

| 文件 | 内容要点 |
|---|---|
| `data/entity/UnitFactEntity.java` | 与 `unit_fact` 逐列对应；`@TableName`/`@TableId`；手写 getter/setter |
| `data/mapper/UnitFactMapper.java` | `@Insert` + `ON CONFLICT (unit_id, unit_version, ts_code, trade_date) DO UPDATE SET payload=excluded.payload, data_status=excluded.data_status`；按单元/日期、按日期+股票查询 |
| `data/entity/UnitVersionEntity.java` | 与 `unit_version` 逐列对应 |
| `data/mapper/UnitVersionMapper.java` | `ON CONFLICT (unit_id, version)` upsert（保留 status，更新 prompt_path/params/activated_at） |
| `data/entity/LlmUsageEntity.java` | 与 `llm_usage` 逐列对应，含新增 `modelProfile` |
| `data/mapper/LlmUsageMapper.java` | `BaseMapper` 即可；按 run_id/created_at 查询留待 T17 |

> SQL 只在 data 层；HTTP/LLM 调用不包事务；批量保存 `unit_fact` 不跨单元批（数量可控），单条 upsert 即可。

### 3.4 config 装配

| 文件 | 内容要点 |
|---|---|
| `config/CapabilityConfig.java` | `@Bean UnitRegistry`、`@Bean FactStore(UnitFactMapper, Clock)`、`@Bean UnitVersionStore(UnitVersionMapper)` |
| `config/UnitRegistryInitializer.java` | `ApplicationRunner @Order(10)`：`ApplicationContext.getBeansWithAnnotation(@CapabilityUnit)` → `UnitRegistry.register` → `UnitVersionStore.sync`；重复/非法单元启动即失败（fail-fast） |
| `common/log/TraceContext.java`（小改） | 增加 `currentRunId()` / `currentRunIdOrNone()`，避免其它类直接读 MDC |

### 3.5 ArchUnit 与规范同步

在 [ArchitectureRulesTest.java](file:///c:/Users/windy/IdeaProjects/refine-loop/src/test/java/com/arthur/refineloop/architecture/ArchitectureRulesTest.java) 增加：

1. `capability` 不得依赖 `ops/tushare/user/web/pipeline/judgment/report/version`。
2. `capability.spi` 不得依赖 `capability.runtime/units` 与 `data`。
3. `capability.runtime` 不得依赖 `capability.units`。
4. `capability.units` 内的单元类不得依赖另一个带 `@CapabilityUnit` 的类（共享工具类不受限）。
5. 既有 `common/data` 反向依赖规则加入 `capability` 包。

同步规范：
- `.claude/rules/01-工程结构与包结构.md` §3 目录树与 §4 依赖矩阵增加 `capability` 行；
- `CLAUDE.md` §3 目录树把 `(规划) capability...` 改为已落地结构；
- `.claude/rules/04-接口契约规范.md` 无需改（4001+ 已登记）。

### 3.6 T6 测试与验收映射

| 测试 | 覆盖验收项 |
|---|---|
| `capability/spi/FactBundleTest` | 并发写入、seal 后不可变、重复 unitId 拒绝、类型读取 |
| `capability/runtime/UnitRegistryTest` | 正常注册、重复 id、缺注解、type/scope 不匹配 |
| `capability/runtime/FactStoreTest`（真实 SQLite，`SchemaTestSupport`） | 写入→读出一致；同键重跑只更新不重复；`data_status` 正确 |
| `capability/runtime/UnitVersionStoreTest` | 注册后 `unit_version` 有 ACTIVE 行、幂等 |
| `capability/CapabilityEndToEndTest`（测试夹具 `DemoComputeUnit`） | 注册→`computeBatch`→`FactStore.saveAll`→读回，全通 |

---

## 4. T7 实施清单

### 4.1 依赖（pom.xml）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

新增依赖（版本由 BOM 管理）：
- `org.springframework.ai:spring-ai-client-chat`
- `org.springframework.ai:spring-ai-openai`
- `org.springframework.ai:spring-ai-anthropic`

> 不加 `spring-ai-starter-model-openai` / `spring-ai-starter-model-anthropic`，避免 auto-config 强制要求全局单一 Key，与多 provider、多协议目标冲突。

### 4.2 配置

| 文件 | 内容要点 |
|---|---|
| `src/main/resources/llm.yml` | `llm.providers.*`、`llm.profiles.fast/strong`、`llm.retry`、`llm.smoke-on-startup`（默认 false） |
| `application.yml` | `spring.config.import` 增加 `optional:classpath:llm.yml` |
| `application-prod.yml` | 删除旧 `llm.dashscope.api-key`，改成新的 provider Key 环境变量占位 |
| `config/LlmProperties.java` | `@ConfigurationProperties(prefix="llm")`；嵌套 `Provider/Profile/Retry`；`Provider` 含 `protocol/baseUrl/apiKey/completionsPath/anthropicVersion`；提供 `requireProfile/requireProvider` |

配置样例见 §5。

### 4.3 `capability/runtime/llm`

| 文件 | 内容要点 |
|---|---|
| `capability/runtime/llm/package-info.java` | 唯一允许接触 `ChatClient` 的包；依赖 spi/common/data/config |
| `LlmProtocol.java` | `OPENAI / ANTHROPIC`；配置里的协议名 |
| `ChatClientProvider.java` | 接口：`ChatClient clientFor(String profile)` + `ChatOptions optionsFor(String profile, Double temperature, Integer maxTokens)`（后者按协议生成 `OpenAiChatOptions` 或 `AnthropicChatOptions`） |
| `LlmChatClientProvider.java` | 按 profile 惰性构建并缓存：校验 provider/api-key → 按 `protocol` 分支：<br>OPENAI：`OpenAiApi.builder().baseUrl(...).apiKey(...).completionsPath(...).restClientBuilder(...)` → `OpenAiChatModel.builder().openAiApi(...).defaultOptions(OpenAiChatOptions...)`<br>ANTHROPIC：`AnthropicApi.builder().baseUrl(...).apiKey(...).completionsPath(...).anthropicVersion(...).restClientBuilder(...)` → `AnthropicChatModel.builder().anthropicApi(...).defaultOptions(AnthropicChatOptions...)`<br>→ `ChatClient.builder(model).build()` |
| `LlmCallSpec.java` | `record(profile, unitId, runId, systemPrompt, userPrompt, temperature, maxTokens)`；`runId` 可空，回退 `TraceContext.currentRunId()` |
| `LlmCallResult.java` | `record(content, model, promptTokens, completionTokens, totalTokens, cost, attempts, durationMs)` |
| `LlmInvoker.java` | 解析 profile → 取 ChatClient/协议化 options → 单次调用带硬超时 → 解析 `ChatResponse` → 记账；自定义重试循环（瞬时/429 分开退避）；错误转 `LlmException` |
| `LlmUsageStore.java` | 写 `llm_usage`（含 model_profile）；按 token 与 profile 单价估 `cost`（HALF_UP, scale=6） |
| `LlmException.java` | 继承 `ApiException`，静态工厂 `notConfigured/timeout/rateLimited/callFailed/promptNotFound` |
| `LlmErrorCode.java` | 4101 CALL_FAILED、4102 TIMEOUT、4103 RATE_LIMITED、4104 NOT_CONFIGURED、4105 PROMPT_NOT_FOUND、4106 RESPONSE_EMPTY、4107 PROMPT_VARIABLE_MISSING |
| `PromptTemplateLoader.java` | 校验 `promptId/version` 正则；加载 `classpath:prompts/{promptId}/{version}.md`；缓存；`render(template, variables)` 替换 `{{key}}`，缺变量抛 4107 |
| `ReportGenerator.java` | `Record ReportRequest(promptId, promptVersion, unitId, runId, modelProfile, variables)`；加载模板→渲染→`LlmInvoker.invoke(fast/strong)`→返回 `ReportOutput(text, call)` |
| `src/main/resources/prompts/daily_summary/v1.md` | “今日一句话”轻量模板（T18 可复用）；只做版本化加载示例 |

### 4.4 data / schema

- `db/schema.sql`：`llm_usage` 在 `unit_id` 后增加 `model_profile VARCHAR(16) NULL`，并更新表注释。
- 同步 `LlmUsageEntity`。
- 开发期按规范删库重建：删除 `data/refine_loop.db`、`-wal`、`-shm` 后启动。
- `SchemaTestSupport.MAPPERS` 增加 `UnitFactMapper`、`UnitVersionMapper`、`LlmUsageMapper`，否则新表测试拿不到 mapper。

### 4.5 启动期冒烟入口

`config/LlmSmokeRunner.java`：
- `ApplicationRunner @Order(20)`，仅当 `llm.smoke-on-startup=true` 时执行；
- 调用 `ReportGenerator.generate(daily_summary v1, 变量={tradeDate: 今天})`；
- 日志输出：profile、model、prompt/completion tokens、usage id；失败只记 error，不阻断应用启动。
- 默认关闭，避免单测/生产误触发真实计费调用。

### 4.6 T7 测试与验收映射

| 测试 | 覆盖验收项 |
|---|---|
| `capability/runtime/llm/LlmInvokerTest`（mock `ChatClientProvider` + mock ChatClient 链） | 成功记账；瞬时错误重试后成功；429 退避后抛出 RATE_LIMITED；超时抛 TIMEOUT |
| `capability/runtime/llm/LlmChatClientProviderTest` | `protocol=OPENAI/ANTHROPIC` 都能构建出 ChatClient（不发网络请求）；Key 为空抛 4104；未知 protocol 拒绝 |
| `capability/runtime/llm/LlmUsageStoreTest`（真实 SQLite） | 一行含 `model_profile/prompt_tokens/completion_tokens/cost` |
| `capability/runtime/llm/PromptTemplateLoaderTest` | 正常加载、缺 prompt 抛 4105、变量替换、缺变量抛 4107、路径非法拒绝 |
| `capability/runtime/llm/ReportGeneratorTest`（mock `LlmInvoker`） | prompt 渲染后传给基座、默认 fast profile、返回文本 |
| `ArchitectureRulesTest` | `ChatClient` 只能出现在 `capability.runtime.llm..` |
| 手工联调 | 用户提供 Key，开启 `llm.smoke-on-startup=true`，确认日志与 `llm_usage` 新增记录 |

---

## 5. 配置样例（`llm.yml`）

```yaml
llm:
  providers:
    deepseek:
      protocol: OPENAI
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${DEEPSEEK_API_KEY:}
      completions-path: /v1/chat/completions
    ark:
      protocol: OPENAI
      # 火山方舟 Coding Plan 的 OpenAI 兼容入口；注意不是 /api/v3
      base-url: ${ARK_BASE_URL:https://ark.cn-beijing.volces.com/api/coding/v3}
      api-key: ${ARK_API_KEY:}
      completions-path: /chat/completions
    dashscope:
      protocol: OPENAI
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${DASHSCOPE_API_KEY:}
      completions-path: /chat/completions
    local-anthropic:
      # 自建 Anthropic Messages 服务（one-api/new-api/LiteLLM/vLLM 等等）
      protocol: ANTHROPIC
      base-url: ${LOCAL_ANTHROPIC_BASE_URL:http://127.0.0.1:3000}
      api-key: ${LOCAL_ANTHROPIC_API_KEY:}
      completions-path: ${LOCAL_ANTHROPIC_COMPLETIONS_PATH:/v1/messages}
      anthropic-version: ${LOCAL_ANTHROPIC_VERSION:2023-06-01}
    claude:
      protocol: ANTHROPIC
      base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
      api-key: ${ANTHROPIC_API_KEY:}
      completions-path: /v1/messages
      anthropic-version: 2023-06-01

  profiles:
    fast:
      provider: ${LLM_FAST_PROVIDER:deepseek}
      model: ${LLM_FAST_MODEL:deepseek-chat}
      temperature: 0.3
      max-tokens: 2048
      timeout-seconds: 120
      input-price-per-1m: 0      # 订阅制/未填单价时成本记 0，token 照常记账
      output-price-per-1m: 0
    strong:
      provider: ${LLM_STRONG_PROVIDER:ark}
      model: ${LLM_STRONG_MODEL:doubao-seed-1-6-250615}
      temperature: 0.4
      max-tokens: 4096
      timeout-seconds: 120
      input-price-per-1m: 0
      output-price-per-1m: 0

  retry:
    max-attempts: 3
    initial-backoff-millis: 1000
    multiplier: 2.0
    rate-limit-backoff-millis: 5000

  smoke-on-startup: false
```

切换方式：只改环境变量或命令行参数。示例：

```powershell
# 自建 Anthropic 协议服务
$env:LOCAL_ANTHROPIC_BASE_URL="http://192.168.1.10:3000"
$env:LOCAL_ANTHROPIC_API_KEY="<your-key>"
java -jar target/refine-loop-*.jar `
  --llm.profiles.fast.provider=local-anthropic `
  --llm.profiles.fast.model=<your-model>
```

---

## 6. 文档同步清单（与代码同一提交）

1. `docs/技术架构与模块设计.md`
   - §0 “AI 框架”改为：Spring AI 1.1.2 + 双协议客户端（`spring-ai-openai` + `spring-ai-anthropic`），支持火山方舟 Coding Plan / DeepSeek / DashScope 兼容模式 / 自建 OpenAI 协议服务 / 自建 Anthropic 协议服务；
   - §1 增加 T7 依赖登记表（`spring-ai-bom`、`spring-ai-openai`、`spring-ai-anthropic`、`spring-ai-client-chat`）与偏差说明。
2. `docs/开发任务计划.md` T7 下增加“实施说明（2026-11-26）”：DashScope starter 改为 OpenAI 兼容客户端的原因与配置方式。
3. `.claude/rules/01-工程结构与包结构.md` §3/§4：增加 `capability` 目录与依赖矩阵。
4. `CLAUDE.md` §3：把 `(规划) capability...` 改为已落地结构。
5. `db/schema.sql` 注释同步 `llm_usage.model_profile`。
6. 不新建额外文档；实施事实写在本计划与上述既有文档中。

---

## 7. 验证步骤（执行顺序）

```powershell
# 1. 后端全量测试（新依赖首次会联网下载 Spring AI）
mvn -B test

# 2. 只跑本次新增测试（快速迭代）
mvn -B -Dtest="FactBundleTest,UnitRegistryTest,FactStoreTest,UnitVersionStoreTest,CapabilityEndToEndTest,LlmInvokerTest,LlmUsageStoreTest,PromptTemplateLoaderTest,ReportGeneratorTest" test

# 3. 开发期 schema 已加列：删库重建
Remove-Item -Force data\refine_loop.db, data\refine_loop.db-wal, data\refine_loop.db-shm -ErrorAction SilentlyContinue

# 4. 真实 LLM 冒烟（需用户 Key；二选一）
# DeepSeek:
$env:DEEPSEEK_API_KEY="<your-key>"
mvn -B -DskipTests package
java -jar target/refine-loop-*.jar --llm.smoke-on-startup=true

# 火山方舟 Coding Plan:
$env:ARK_API_KEY="<your-key>"
$env:LLM_FAST_PROVIDER="ark"
$env:LLM_FAST_MODEL="<你的 Coding Plan 模型>"
java -jar target/refine-loop-*.jar --llm.smoke-on-startup=true

# 自建 Anthropic 协议服务:
$env:LOCAL_ANTHROPIC_BASE_URL="http://127.0.0.1:3000"
$env:LOCAL_ANTHROPIC_API_KEY="<your-key>"
java -jar target/refine-loop-*.jar `
  --llm.profiles.fast.provider=local-anthropic `
  --llm.profiles.fast.model="<你的模型>" `
  --llm.smoke-on-startup=true
```

验收对照：
- `mvn -B test` 全绿；ArchUnit 新规则通过。
- `llm_usage` 冒烟行含 `model_profile` 与 token 数（由 `LlmSmokeRunner` 日志 + 数据库查询确认）。
- 未配置 Key 时应用正常启动，只有调用 LLM 的代码路径报 `LLM_NOT_CONFIGURED`。

---

## 8. 风险与假设

| 风险/假设 | 处置 |
|---|---|
| 本机无 Spring AI 依赖缓存，首次 `mvn test` 需联网 | 构建前确认网络/Maven 镜像可用；失败先解决依赖下载，不改实现方案 |
| Spring AI 1.1.2 的 `OpenAiApi.Builder` 具体方法名（如 `restClientBuilder`/`completionsPath`）与文档存在版本差异 | 以编译结果为准；优先用 builder 链，若方法名不符按 1.1.2 实际 API 调整，不改变分层与配置结构 |
| 火山方舟/DeepSeek 对某些 OpenAI 字段兼容性不同 | 只使用 `model/temperature/max_tokens/stream=false` 等基础字段；`responseFormat` 不在 T7 使用 |
| 自建 Anthropic 网关的鉴权/版本头差异 | Spring AI 按 `x-api-key` + `anthropic-version` 调用；自建网关需接受这两个头（one-api/new-api/LiteLLM 默认支持）。若只接受 `Authorization: Bearer`，在网关侧补兼容，不在客户端做协议魔改 |
| Anthropic 协议的 `max_tokens` 等字段与 OpenAI 语义不同 | 由 Spring AI 的 `AnthropicChatOptions` 负责映射；业务代码只传统一语义的 temperature/maxTokens |
| 真实调用的 token 计费价格不稳定 | 价格只作为可配置估算，默认 0；不写死厂商价格 |
| `llm_usage` 加列导致旧库不兼容 | 开发期按规范删库重建；1.0 后改走 `db/migration/V*.sql`（本次不涉及） |
| 注释/接口双源漂移 | 接口默认方法直接读注解，注册期校验，杜绝两处各写一份 |

---

## 9. 执行顺序（建议 todo）

1. pom 依赖/BOM + `llm.yml` + `LlmProperties` 骨架（先保证能编译下载）。
2. T6 SPI + runtime + data 实体/Mapper + config 装配。
3. T6 测试（含 demo compute 夹具与 ArchUnit）。
4. T7 `llm_usage` 加列 + `LlmUsageEntity/Mapper`。
5. T7 `capability/runtime/llm` 全量实现（OpenAI + Anthropic 双协议）+ `config/LlmConfig` + `LlmSmokeRunner`。
6. T7 测试（mock ChatClient / SQLite / prompt）。
7. 文档同步（架构 §0/§1、任务计划 T7 说明、规范 01、CLAUDE.md）。
8. `mvn -B test` 全绿；用户提供 Key 后完成真实冒烟验收。
