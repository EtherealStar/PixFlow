# 完整实现 module/dag：DAG 编译/校验/分支展开/确定性单元执行器

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 `module/dag` 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级 DAG 确定性执行引擎。它的核心价值是**把 Agent 产出的 DAG JSON 提案，变成可重复、可恢复、可观测的批量像素处理流水线**。

用户能看到的效果是：在前端看到 Agent 提交的「去背景白底+压缩 800×800 JPEG」处理方案，确认后系统把 800 张图片在数分钟内跑完；中间任何一张图损坏、某张抠图接口超时、某个组的视图缺失，都只影响对应的工作单元而不阻断整批；任务被 kill 后重启能从断点继续；运维面板能看到每个像素工具的调用次数与失败率。

本计划不是 MVP。实现必须覆盖完整的 DAG IR、参数 schema 校验、确定性 branchId 派生、组支路 fan-in、确定性单元执行器（缝合 storage→thirdparty→image→ai→storage 五段）、资源生命周期、超时与取消协作、可观测指标、提案入队与确认、Testcontainers 集成测试，以及一组能证明"确定性底座不被污染"的单元测试。

## 设计文档快速定位关键词

执行本计划时，所有架构与边界决策以 `docs/design-docs/module/dag.md` 为唯一权威。下列关键词可在该文档内快速定位对应段落：

- 模块核心边界（dag 是确定性库、task 是异步外壳）→ 搜 `dag 是确定性库` 或 `dag/task 边界`
- 像素工具白名单与 `PixelTool` 枚举 → 搜 `像素工具白名单`
- DagValidator 校验顺序（含 S8 remove_bg 链首约束） → 搜 `DagValidator` 与 `remove_bg 必须是首个`
- Canonical Form 规则（branchId 与 payload_hash 的共同基石）→ 搜 `Canonical Form 规则`
- 提案与确认边界（pending_plan、HITL、双重校验）→ 搜 `pending_plan`
- Schema 演进约束（加法兼容 + 大版本升级）→ 搜 `Schema 演进`
- BranchExpander 分支与组支路展开 → 搜 `BranchExpander`
- 单元执行器接口与产物（UnitOutcome）→ 搜 `UnitExecutor` 与 `UnitOutcome`
- 资源生命周期（BranchExecutionContext）→ 搜 `资源生命周期`
- generate_copy 独立文案分支 → 搜 `generate_copy`
- 错误归一化与 DagErrorCode → 搜 `DagErrorCode` 或 `错误归一化`
- 中间产物引用走 state、缓存收窄到组支路 → 搜 `group:cache` 或 `缓存收窄`
- 任务级共享素材缓存（watermark） → 搜 `任务级共享素材`
- 超时与可中断、dag 不持取消标志 → 搜 `超时与可中断`
- 配置（pixflow.dag.*） → 搜 `@ConfigurationProperties`
- 可观测指标清单 → 搜 `可观测性`
- 测试策略 → 搜 `测试策略`
- 反向依赖约束（dag 不依赖 file/task/conversation/...） → 搜 `反向约束`

辅助参考：`docs/design-docs/design.md` 整体架构与数据模型；`docs/design-docs/infra/image.md` 类型化像素操作；`docs/design-docs/infra/storage.md` MinIO I/O 缝合；`docs/design-docs/infra/thirdparty.md` 抠图 Resilience4j 边界；`docs/design-docs/infra/ai.md` 文案 ChatModelClient；`docs/design-docs/harness/state.md` RunStateRefStore；`docs/design-docs/harness/tools.md` SubmitImagePlanHandler 倒置接缝；`docs/design-docs/base/common.md` ErrorCode 契约与错误归一化。

## Progress

- [x] (2026-06-29 02:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`module-dependency-dag-plan.md`、`design.md`、`module/dag.md`、`infra/image.md`、`infra/storage.md`、`infra/thirdparty.md`、`infra/ai.md`、`harness/state.md`、`harness/tools.md`、`base/common.md`，确认 dag 是 Wave 3 确定性核心，依赖 `infra/{image,ai,thirdparty,storage}`、`harness/state`、`common`，被 `harness/tools`、`module/task`、`module/conversation` 消费。
- [x] (2026-06-29 02:30+08:00) 与用户对齐 9 个生产级细节：Canonical Form、remove_bg 链首约束、错误归一化细分、schema 演进、资源生命周期、watermark 缓存、超时、大图防护、可观测指标，并把这些决策写回 `module/dag.md`。
- [x] (2026-06-29 02:30+08:00) 创建本执行计划，明确 dag 模块的完整架构思路、机制、文件改动顺序、验证命令和关键词索引。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 1：创建 `pixflow-module-dag` Maven 模块骨架，加入根 `pom.xml` 与 `pixflow-app` 依赖，源码包 `com.pixflow.module.dag`。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 1：实现 IR（`DagDocument` / `DagNode` / `DagEdge` / `PixelTool` / `ValidatedDag`）、`DagJsonReader`、Canonical JSON 工具（`CanonicalJson`）、`DagValidator` + 7 个规则类、`ParamSchemaRegistry` + 8 个 JSON Schema 资源文件（`remove_bg`/`set_background`/`resize`/`compress`/`watermark`/`convert_format`/`compose_group`/`generate_copy`）。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 1：实现 `BranchExpander`（含 canonical branchId 派生）、`ExecutableBranch`/`BranchId`/`ImageDescriptor`/`GroupPreflight`。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 1：纯函数核心单测（JUnit + AssertJ），覆盖 validator 全部规则、branchId 确定性、GroupPreflight 差异计算；`mvn -pl pixflow-module-dag -am test` 全绿。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 2：实现 `UnitOutcome` / `UnitInput` / `CopyContext` / `DagErrorCode`（并入 `common` 启动期聚合测试）、`SpecMapper` / `NodeDispatcher` / `BranchExecutionContext`。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 2：实现 `PipelineUnitExecutor` 骨架（fake infra 桩），覆盖失败注入测试——任何阶段失败都返回 `UnitOutcome.FAILED`，永不抛出业务异常；超时与大图防护测试。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 2：`mvn -pl pixflow-module-dag -am test` 全绿，含失败隔离边界测试。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 3：实现 `GroupUnitExecutor`（含 `runComposed` 调用与缺图归一化）、`CopyUnitExecutor`（`ChatModelClient` 集成）、`TaskAssetCache`（watermark 进程内 Caffeine 缓存）。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 3：集成 `infra/{image,thirdparty,storage,ai}` 真实实现；Testcontainers MinIO 集成测试覆盖「去背景+白底+resize+压缩」整条支路。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 3：资源生命周期集成测试（各阶段失败 dispose 计数桩）；并发安全测试（多线程并发跑 `PipelineUnitExecutor`，WeakReference 跟踪）。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 3：`mvn -pl pixflow-module-dag -am test` 全绿，含集成测试与 Testcontainers（按 env 跳过策略）。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 4：实现 `pending_plan` 表 + MyBatis-Plus mapper、`PendingPlanService`（enqueue/状态迁移/幂等/`payload_hash`/`schema_version`）、`PendingPlanStatus` 枚举。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 4：实现 `SubmitImagePlanHandler`（贡献给 `harness/tools` 的 `ToolDescriptor`/`ToolHandler` bean），含 dag 浅层校验 + 深校验 + 入队。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 4：实现 schema 资源文件版本管理 + 启动期 schema 自检（`SchemaRegistryValidator`）；`mvn -pl pixflow-module-dag -am test` 全绿。
- [ ] (2026-06-29 ??:??+08:00) 里程碑 4：`mvn -pl pixflow-app -am test` 全绿；端到端冒烟：构造 `submit_image_plan` JSON 入参 → handler 入队 → 确认 REST 边界（`module/conversation` 模拟）→ `DagValidator` 重校验 → `payload_hash` 一致性校验。

## Surprises & Discoveries

- Observation: `design.md §9.4` 把「支路幂等：恢复时整条支路重跑」放在断点恢复段，但 `design.md §12` 字面上把「执行引擎」列在 `module/dag`、`「失败隔离」列在 `module/task`，两条边界字面含混。
  Evidence: `design.md` 搜 `执行引擎` 与 `失败隔离`；`docs/design-docs/module/dag.md` §二已把这条线钉死为「dag 是无状态确定性单元执行器 + 校验 + 展开；task 是有状态异步外壳」。

- Observation: 原 `module-dependency-dag-plan.md` 依赖图画的是 `cache → dag` 直连边，与 `harness/state` 的「dag 经 `RunStateRefStore`」契约冲突。
  Evidence: `module-dependency-dag-plan.md` 与 `harness/state.md §七` 对照；`docs/design-docs/module/dag.md` §十一已采纳 state facade，建议 `module-dependency-dag-plan.md` 删 `cache → dag`、补 `state → dag`。

- Observation: 原 `module/file.md` 契约表写「dag 读 `asset_image`」，与 `module/dag.md` 的「dag 不依赖 file、分组成员以中立 record 喂入」冲突。
  Evidence: `docs/design-docs/module/file.md` 搜 `dag`；`docs/design-docs/module/dag.md` §七、§十五已记录这个回写决策。

- Observation: `infra/image` 的 `ImagePipeline.run` 不持有源 RasterImage 跨 op 的契约，意味着每 op 后必须立即 dispose 上游 RasterImage，否则 native 内存峰值 = 链上所有中间结果之和。
  Evidence: `docs/design-docs/infra/image.md` §七（解码一次/编码一次流水线）；`docs/design-docs/module/dag.md` §8.5 资源生命周期。

## Decision Log

- Decision: 把 dag 模块拆为 4 个里程碑（纯函数核心 → 执行器骨架 → I/O 缝合 → 提案与确认）。
  Rationale: 纯函数核心（IR/validator/expander）零外部依赖，可独立完整单测，第一周就能跑通；执行器骨架依赖 fake infra 桩，能在集成测试前发现接口不匹配；I/O 缝合是 Testcontainers 集成测试主场；提案与确认需要 harness/tools 的 ToolHandler 接缝，依赖最外延。
  Date/Author: 2026-06-29 / Kiro

- Decision: `branchId = sha256(canonicalJson(sourceSinkPath) + "|" + sha256(canonicalJson(nodeParams)))`，节点路径 + 每节点 canonical JSON 哈希拼接。
  Rationale: 路径串（`n1->n3->n7`）只看结构不看参数——同一结构不同参数会被判同 branchId，错误；内容哈希既看结构又看参数，正确但调试不友好；拼接形式 = 可读 + 稳定，hash 仅用于匹配判定，路径串供 debug 日志。
  Date/Author: 2026-06-29 / Kiro

- Decision: `payload_hash` 与 branchId 共享同一套 `CanonicalJson` 规范化函数。
  Rationale: 确认一致性的 hash 与恢复语义的 hash 必须口径一致；分散定义会导致 dag 升级时两边漂移。共享函数 + 共享测试 = 单一事实源。
  Date/Author: 2026-06-29 / Kiro

- Decision: 加 `remove_bg` 必须是逐图节点序列首位的约束，违例抛 `DAG_INVALID_OP_ORDER`。
  Rationale: 先 resize 再抠图 = 把整张图发送给第三方抠图服务，再让 image 二次处理 = 资源浪费 + 抠图结果未必针对最终尺寸。约束在 `DagValidator` 阶段拒绝，保证下游不会出现这种链。
  Date/Author: 2026-06-29 / Kiro

- Decision: schema 演进走"加法兼容 + 大版本升级"。破坏性变更需升级 schema 大版本号（如 `1.x` → `2.0`），同时所有 `PENDING` 旧版本 plan 立即标 `EXPIRED`。
  Rationale: 加法兼容保证存量 DAG 不被破坏；大版本升级提供清晰断点。回滚时旧版 dag 仍能用旧 schema 校验（保留 N=2 个大版本）。
  Date/Author: 2026-06-29 / Kiro

- Decision: 组支路源 MinIO 读失败按 `retryable` / `non-retryable` 走两条路径——前者整组 SKIP 重试，后者整批任务 FAILED。
  Rationale: 4xx/NoSuchKey 是数据问题，重试无用，整批失败避免浪费后续算力；5xx/网络是基础设施抖动，整组 SKIP 让 task 整体重试有机会修复。两种归一化对运维意义不同。
  Date/Author: 2026-06-29 / Kiro

- Decision: watermark 缓存放 dag 进程内（Caffeine），不写 Redis。
  Rationale: watermark 图被同一 task 内 N 张主图复用，进程内复用避免重复 MinIO 拉取 + decode；MinIO 已经是跨进程共享存储，跨进程缓存等于把多 worker 协调的复杂度带进来，但收益（避免一次跨进程拉取）远小于复杂度成本。
  Date/Author: 2026-06-29 / Kiro

- Decision: dag 单元执行器**不持取消标志**——task worker 在调用 `UnitExecutor.execute` **之前**调 `CancellationReader.throwIfCancelled`，不在执行内部检查。
  Rationale: 避免 `state` 模块被引入 dag 单元热路径；取消是协作式（cooperative）而非抢占式，符合"确定性底座不被污染"原则。
  Date/Author: 2026-06-29 / Kiro

- Decision: dag 通过 Micrometer 暴露 11 类指标，**不依赖 `harness/eval`**。
  Rationale: 与 `harness/tools` 同款做法，honor 依赖 DAG 中 `dag` 无 `→ eval` 边；指标由 Spring Boot Actuator 端点暴露，Prometheus/Grafana 直接消费。
  Date/Author: 2026-06-29 / Kiro

## Outcomes & Retrospective

（待全部里程碑完成后回填）

## Context and Orientation

### 当前状态

`docs/design-docs/module/dag.md` 已完成生产级细化设计，包括 9 处本轮新增的段落（Canonical Form、remove_bg 链首、schema 演进、错误归一化细分、资源生命周期、watermark 缓存、超时与可中断、可观测性、配置扩展）。仓库根目录 `pom.xml` 已有 `pixflow-common`、`pixflow-infra-image`、`pixflow-infra-storage`、`pixflow-infra-thirdparty`、`pixflow-infra-ai`、`pixflow-harness-state`、`pixflow-harness-tools` 等依赖可被 `pixflow-module-dag` 引用。`pixflow-tools` 模块已存在并实现了 ToolRegistry 倒置接缝，等待 `SubmitImagePlanHandler` 的 bean 贡献。

### 关键术语

- **DAG IR（Intermediate Representation）**：dag 模块解析 Agent 产出 JSON 后形成的内存数据结构，含 `DagDocument`（浅解析，未校验）和 `ValidatedDag`（校验通过，可安全展开）。
- **PixelTool**：dag 拥有的 8 个像素工具枚举（`remove_bg`/`set_background`/`resize`/`compress`/`watermark`/`convert_format`/`compose_group`/`generate_copy`），封闭不可扩展。
- **branchId**：单条 source→sink 路径的确定性派生 ID，用于断点恢复时的单元匹配。
- **ExecutableBranch**：BranchExpander 展开后的可执行支路，含 `perMemberOps` / `composeNode` / `postOps` / `EncodeTarget`。
- **UnitOutcome**：单元执行器的中立产物，含 SUCCEEDED(outputKey) 或 FAILED(DagErrorView)；task 据此写 `process_result`。
- **Canonical Form**：JSON 规范化形式（字典序键排序、缺失字段补 null、数字最简化、UTF-8 NFC 等），branchId 与 payload_hash 的共同基石。
- **pending_plan**：dag 提案载体，独立表（不复用 `process_task` 状态机），对 IMAGE_PLAN/IMAGEGEN 两类提案中立。

### 模块结构与依赖位置

源码包：`com.pixflow.module.dag`。Maven 模块：`pixflow-module-dag`（需加入根 `pom.xml` `<modules>`）。

```
module/dag/
├── DagFacade.java                # 对外门面：validate / proposeImagePlan / expand / executeUnit / preflightGroups
├── ir/                           # IR 与解析
├── validate/                     # DagValidator + ParamSchemaRegistry + 规则
├── propose/                      # PendingPlan + SubmitImagePlanHandler
├── expand/                       # BranchExpander + ExecutableBranch + CanonicalJson
├── exec/                         # UnitExecutor 三种实现 + NodeDispatcher + SpecMapper + BranchExecutionContext
├── cache/                        # TaskAssetCache（watermark）
├── error/                        # DagErrorCode
└── config/                       # DagProperties + DagAutoConfiguration
```

依赖方向（实现期严格遵守，违反则单元测试用 ArchUnit 守护）：

```
module/dag ──► infra/image, infra/thirdparty, infra/ai, infra/storage
module/dag ──► harness/state（仅 RunStateRefStore）
module/dag ──► common（PixFlowException / ErrorNormalizer / Sanitizer / ErrorCode）
module/dag ──► MySQL/MyBatis-Plus（pending_plan 表）
```

反向硬约束（ArchUnit 测试断言）：`com.pixflow.module.dag..` **不依赖** `module/file` / `module/task` / `module/conversation` / `harness/loop` / `agent` / `infra/cache` / `contracts`。不出现线程池、MQ、Redisson、`process_result` 实体直连。

## Plan of Work

### 里程碑 1：纯函数核心（最优先、最易测、零外部依赖）

**目标**：dag 模块骨架 + IR + Validator + Expander 全部用纯函数实现，能脱离 Spring 上下文完整单测。

**新增文件**：

- `pixflow-module-dag/pom.xml`：依赖 `pixflow-common`（基础错误模型）、`networknt:json-schema-validator`（schema 校验）、`jackson-databind`（canonical JSON）。
- 根 `pom.xml` 的 `<modules>` 增加 `<module>pixflow-module-dag</module>`。
- `src/main/java/com/pixflow/module/dag/ir/`：`DagNode.java`、`DagEdge.java`、`DagDocument.java`、`ValidatedDag.java`、`PixelTool.java`、`DagJsonReader.java`、`CanonicalJson.java`。
- `src/main/java/com/pixflow/module/dag/validate/`：`DagValidator.java`、`DagValidationResult.java`、`ParamSchemaRegistry.java`，以及 `rule/` 子包下的 7 个规则类（StructureRule / NodeLimitRule / WhitelistRule / ParamsRule / EdgeRule / AcyclicRule / GroupBranchRule / OpOrderRule，分别对应 S1-S8）。
- `src/main/resources/schemas/dag/`：8 个 JSON Schema 文件，每个带 `"version": "1.0"` 与 `"tool": "..."` 顶层字段。
- `src/main/java/com/pixflow/module/dag/expand/`：`BranchExpander.java`、`ExecutableBranch.java`、`BranchId.java`、`ImageDescriptor.java`、`GroupPreflight.java`。
- `src/test/java/com/pixflow/module/dag/`：纯函数单测，`mvn test` 不依赖 Spring 上下文。

**关键实现细节**：

- `PixelTool` 是 enum，每个值带 `arity`（`ONE_TO_ONE` / `N_TO_ONE` / `TEXT`）、`targetInfra`（THIRDPARTY/IMAGE/AI）、`specClass`（用于 SpecMapper）。编译期不可新增。
- `CanonicalJson` 用专用 `ObjectMapper`：开启 `SORT_PROPERTIES_ALPHABETICALLY`、自定义数字序列化器（去尾随零）、自定义 null 序列化器（区分 null 与缺失）、`SerializationFeature.WRITE_DATES_AS_TIMESTAMPS=false`。纯函数，零状态。
- `DagValidator` 把 8 条规则串行跑，任一失败把错误项加入 `DagValidationResult.errors`（不抛异常，由调用方决定如何处理）。
- `BranchExpander` 用 `ValidatedDag` 的拓扑序枚举所有 source→sink 路径；含 `compose_group` 的路径拆为「perMemberOps + composeNode + postOps」三段；branchId 按 §7.4 拼接形式派生。**不持任何运行时状态**。

**测试要点**：

- validator 单测覆盖每个规则（含 S8 remove_bg 链首的 5 个边界用例）；无状态可重复调用同输入同结果。
- ParamSchemaRegistry 单测覆盖每个 tool 的 schema 命中/越界（如 resize mode 枚举、compress quality∈[1,100]）。
- BranchExpander 单测覆盖：① 单节点后分多支路；② 组支路 perMember/compose/post 拆分；③ 普通支路无 compose；④ **确定性 branchId**（同 DAG 同路径多次展开 id 稳定）；⑤ 中立 ImageDescriptor 输入驱动。
- GroupPreflight 单测覆盖 expected_count 与实际成员数一致/不一致。

**验证**：`mvn -pl pixflow-module-dag -am test`，全部单测绿；`mvn -pl pixflow-module-dag compile` 不依赖 Spring。

### 里程碑 2：执行器骨架 + 错误归一化

**目标**：dag 模块能让 fake infra 桩跑通单条支路，覆盖失败注入、超时、大图防护、native 资源清理。

**新增文件**：

- `src/main/java/com/pixflow/module/dag/exec/`：`UnitExecutor.java`（接口）、`PipelineUnitExecutor.java`（骨架）、`NodeDispatcher.java`、`SpecMapper.java`、`UnitInput.java`、`UnitOutcome.java`、`BranchExecutionContext.java`。
- `src/main/java/com/pixflow/module/dag/error/`：`DagErrorCode.java`（implements common.ErrorCode）。
- `src/main/java/com/pixflow/module/dag/config/`：`DagProperties.java`（`@ConfigurationProperties("pixflow.dag")`）。

**关键实现细节**：

- `UnitOutcome` 是 record：`status`, `outputObjectKey`, `generatedCopy`, `members`, `error`（`DagErrorView` 只含 code + safeMessage + category）。
- `BranchExecutionContext` 用 `ArrayDeque<Runnable>` 做 LIFO 清理栈；`close()` 由 try-with-resources 保证；提供 `onDispose(RasterImage)` / `onClose(InputStream)` / `onCancel(Runnable)`。
- `PipelineUnitExecutor.execute` 用 `Future.get(unitTimeout)` 实现超时；超时后 `future.cancel(true)` 触发中断。
- `SpecMapper` 把 `DagNode.params` 映射为类型化 spec（`ResizeSpec`/`CompressSpec`/...），含防御式兜底（缺必填字段抛 `DagErrorCode.DAG_INVALID_PARAMS`）。
- `NodeDispatcher` 是封闭枚举映射；`remove_bg` 路由到 `infra/thirdparty.BackgroundRemovalClient`，其余逐图工具路由到 `infra/image.ImagePipeline`，`generate_copy` 路由到 `infra/ai.ChatModelClient`。

**测试要点**：

- 失败注入：用 fake `ImageCodec` / `BackgroundRemovalClient` / `ChatModelClient`，注入各阶段抛异常，断言 `PipelineUnitExecutor` 返回 `UnitOutcome.FAILED`，**永不抛出业务异常**。
- 超时：fake infra `Thread.sleep(超时+1s)`，断言 `FAILED(DAG_UNIT_TIMEOUT)`，不写 `process_result`（单元测试层不落库）。
- 大图防护：fake `ObjectStorage.stat` 返回超大 size，断言不调用 `getStream`（计数桩）。
- 资源清理：fake `RasterImage` 实现 dispose 计数；注入各阶段失败，断言所有 `RasterImage` 的 dispose 都被调用。
- ArchUnit 测试断言 `com.pixflow.module.dag..` 不依赖任何禁止模块。

**验证**：`mvn -pl pixflow-module-dag -am test`，全部单测绿。

### 里程碑 3：I/O 缝合 + 资源生命周期 + 集成测试

**目标**：dag 与 `infra/{image,thirdparty,storage,ai}` 真实实现集成，Testcontainers MinIO 跑通「去背景+白底+resize+压缩」整条支路。

**新增文件**：

- `src/main/java/com/pixflow/module/dag/exec/GroupUnitExecutor.java`：组支路执行器，遍历成员 viewId 排序后调 `image.runComposed`。
- `src/main/java/com/pixflow/module/dag/exec/CopyUnitExecutor.java`：文案支路，调 `ChatModelClient`。
- `src/main/java/com/pixflow/module/dag/cache/TaskAssetCache.java`：Caffeine 进程内缓存。
- `src/main/java/com/pixflow/module/dag/DagFacade.java`：对外门面，聚合 `validate` / `proposeImagePlan` / `expand` / `executeUnit` / `preflightGroups`。
- `src/test/java/com/pixflow/module/dag/integration/`：Testcontainers 集成测试。

**关键实现细节**：

- `GroupUnitExecutor` 对组内每个成员 m（按 viewId 排序）：`storage.getStream(m.objectKey)` → 施加 perMemberOps → 缓存预处理结果到 `group:cache` → 整组完成后调 `image.runComposed` → `storage.put(StorageKeys.groupResult(...))`。
- `TaskAssetCache` 用 Caffeine，`maximumSize = max-entries-per-task × taskCount`，`expireAfterAccess = task TTL`；task 结束事件回调清空。
- 集成测试用 `@Testcontainers` + MinIO 容器，跑「80 张图 → 去背景 → 白底 → resize 800×800 → 压缩 quality 85」全链路，断言结果 MinIO key 全部存在且 output_size 合理。

**测试要点**：

- Testcontainers 集成测试（按 env 跳过策略，本地必跑）。
- 资源生命周期集成测试（WeakReference + System.gc 跟踪无残留）。
- 并发安全测试（多线程并发跑 `PipelineUnitExecutor`）。
- watermark 缓存命中测试（同一 task 两条支路用同一 watermark → cache hit == 1）。
- 缺图归一化测试（fake storage 某成员读失败 → 整组 FAILED，`details.missingViews` 含失败 view）。

**验证**：`mvn -pl pixflow-module-dag -am test`，单测 + 集成测试全绿；Testcontainers 按 `infra/storage` 已有 profile 跳过策略（`windows-docker-tcp` / 环境变量 `DOCKER_HOST`）。

### 里程碑 4：提案与确认接线

**目标**：`pending_plan` 表 + `SubmitImagePlanHandler` 接入 `harness/tools`，端到端冒烟走通。

**新增文件**：

- `src/main/resources/db/migration/Vxxx__create_pending_plan.sql`：表结构（含 `schema_version` 列）。
- `src/main/java/com/pixflow/module/dag/propose/`：`PendingPlan.java`、`PendingPlanStatus.java`、`PendingPlanService.java`、`PendingPlanMapper.java`。
- `src/main/java/com/pixflow/module/dag/propose/SubmitImagePlanHandler.java`：实现 `harness/tools.ToolHandler` 接口，贡献 `ToolDescriptor` bean。
- `src/main/java/com/pixflow/module/dag/validate/SchemaRegistryValidator.java`：启动期 schema 自检（版本单调、code 唯一）。
- `src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java`：装配所有 bean。

**关键实现细节**：

- `pending_plan` 表字段：`id`, `conversation_id`, `type`（IMAGE_PLAN/IMAGEGEN）, `dag_json`, `payload_hash`, `schema_version`, `note`, `status`, `created_at`, `expires_at`, `confirmed_at`, `task_id`。
- `PendingPlanService.enqueue(conversationId, ValidatedDag, note)` 幂等（同 toolCallId 重复不产生重复 plan）；计算 `payload_hash` 用 `CanonicalJson`。
- `SubmitImagePlanHandler` 在 `handle` 内：dag 浅层校验（合法 JSON object、含 nodes/edges）→ `DagValidator` 深校验 → `PendingPlanService.enqueue` → 返回 planId。
- `pixflow-app` 的 `@Scheduled` 任务调用 `PendingPlanService.expireOverdue()` 清理过期 PENDING。

**测试要点**：

- `SubmitImagePlanHandler` 测试：合法 DAG → 入队返回 planId；非法 DAG → 结构化 tool error 不入队；同 toolCallId 幂等。
- `pending_plan` 状态机测试：PENDING → CONFIRMED / DISCARDED / EXPIRED。
- payload_hash 稳定计算（规范化 DAG）。
- schema_version 不一致时确认拒绝（`DAG_SCHEMA_INCOMPATIBLE`）。
- 端到端冒烟：构造 `submit_image_plan` JSON → handler 入队 → 模拟确认 REST（`module/conversation` 的 fake controller）→ `DagValidator` 重校验 + `payload_hash` 一致性校验通过 → 创建 `process_task`（fake）。

**验证**：`mvn -pl pixflow-module-dag -am test` 全绿；`mvn -pl pixflow-app -am test` 全绿（dag bean 被 app 装配后不影响现有 app 测试）。

## Concrete Steps

### 工作目录

所有命令在 `D:\study\PixFlow`（PowerShell）下运行。

### 里程碑 1 命令序列

```
# 创建模块骨架
mkdir pixflow-module-dag/src/main/java/com/pixflow/module/dag/{ir,validate/rule,expand}
mkdir pixflow-module-dag/src/main/resources/schemas/dag
mkdir pixflow-module-dag/src/test/java/com/pixflow/module/dag/{ir,validate,expand}

# 写入 pom.xml（参考 pixflow-tools/pom.xml 的依赖结构）
# 在根 pom.xml <modules> 加入 <module>pixflow-module-dag</module>

# 写 IR/Validator/Expander 源码 + schema 资源文件 + 单测

# 验证
mvn -pl pixflow-module-dag -am compile
mvn -pl pixflow-module-dag test
```

预期输出：`mvn test` 末尾出现 `Tests run: 80+, Failures: 0, Errors: 0, Skipped: 0`；`mvn compile` 无错误（仅依赖 `pixflow-common` 与 Jackson/networknt）。

### 里程碑 2 命令序列

```
mkdir pixflow-module-dag/src/main/java/com/pixflow/module/dag/{exec,error,config}
mkdir pixflow-module-dag/src/test/java/com/pixflow/module/dag/{exec,error}

# 写 UnitOutcome/Executor/Context/ErrorCode + 单测

# 验证
mvn -pl pixflow-module-dag -am test
```

预期：`Tests run: 150+, Failures: 0, Errors: 0, Skipped: 0`；ArchUnit 守护测试绿。

### 里程碑 3 命令序列

```
mkdir pixflow-module-dag/src/main/java/com/pixflow/module/dag/cache
mkdir pixflow-module-dag/src/test/java/com/pixflow/module/dag/integration

# 写 GroupUnitExecutor/CopyUnitExecutor/TaskAssetCache/DagFacade + 集成测试

# 验证
mvn -pl pixflow-module-dag -am test
```

预期：单测全绿；Testcontainers 集成测试在本地 Docker 可用时跑通，按 `infra/storage` 既有 profile 跳过策略处理 Docker 不可用情况。

### 里程碑 4 命令序列

```
mkdir pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose
mkdir pixflow-module-dag/src/main/resources/db/migration

# 写 pending_plan SQL + Mapper + Service + Handler + AutoConfiguration

# 在 pixflow-app 的 pom.xml 加 pixflow-module-dag 依赖（如果还没有）

# 验证
mvn -pl pixflow-module-dag -am test
mvn -pl pixflow-app -am test
```

预期：dag 测试 + app 测试全绿；`mvn -pl pixflow-app spring-boot:run` 启动后能看到 dag bean 被装配。

### 端到端冒烟（里程碑 4 验证用）

```
# 构造 submit_image_plan JSON 入参
$dag = @'
{"nodes":[{"id":"n1","tool":"set_background","params":{"color":"#FFFFFF"}}],"edges":[]}
'@
$payload = @{ dag = $dag; note = "白底测试" } | ConvertTo-Json

# POST 到 submit_image_plan handler（通过 app 的 REST 端点）
Invoke-WebRequest -Uri http://localhost:8080/api/tools/submit_image_plan `
  -Method POST -Body $payload -ContentType "application/json"
```

预期：返回 `{"success":true, "data":{"planId":"...","summary":"..."}}`；`pending_plan` 表新增一条 PENDING 记录。

## Validation and Acceptance

### 行为级验收（人工可观察）

1. **DAG 校验**：构造含未知 tool 的 DAG（如 `tool:"unknown_op"`），submit 后应收到 `DAG_UNKNOWN_TOOL` tool error，pending_plan 不新增。
2. **remove_bg 链首约束**：构造 `resize → remove_bg` 链，submit 后应收到 `DAG_INVALID_OP_ORDER` tool error。
3. **Schema 校验**：构造 `resize` 缺必填 `width`，submit 后应收到 `DAG_INVALID_PARAMS` tool error。
4. **branchId 确定性**：构造相同语义的 DAG（仅字段顺序不同），两次展开得到相同的 branchId。
5. **失败隔离**：构造含损坏图的 DAG（fake storage 注入 `IIOException`），单元执行器返回 `FAILED(IMAGE_PROCESSING)`，不抛出业务异常。
6. **超时**：构造会 hang 的 fake 第三方，单元执行器在 `unit-timeout` 秒内返回 `FAILED(DAG_UNIT_TIMEOUT)`。
7. **大图防护**：fake storage stat 返回 300MB，单元执行器不调 `getStream`，直接返回 `FAILED(DAG_SOURCE_BYTES_TOO_LARGE)`。
8. **资源清理**：fake RasterImage 计数桩，注入各阶段失败，断言所有 RasterImage 的 dispose 被调用。
9. **watermark 缓存命中**：同 task 两条支路用同一 watermark → cache hit 计数 == 1。
10. **payload_hash 一致**：同语义 DAG 两次入队 payload_hash 相同；不同语义 DAG hash 不同。
11. **端到端**：构造 `submit_image_plan` → handler 入队 → 模拟确认 → `DagValidator` 重校验通过 → 模拟 task worker 调 `UnitExecutor.execute` → 模拟 MinIO 写入 → 模拟 process_result 落库（fake）。

### 测试命令

```
# 单测
mvn -pl pixflow-module-dag -am test

# 集成测试（Testcontainers 需要 Docker）
mvn -pl pixflow-module-dag -am test -Pintegration

# 端到端冒烟（pixflow-app 启动后）
mvn -pl pixflow-app spring-boot:run
```

### 验收判定

- `mvn -pl pixflow-module-dag -am test` 全绿（含 ArchUnit 守护测试）
- `mvn -pl pixflow-app -am test` 全绿（dag bean 装配不影响 app 现有测试）
- `mvn -pl pixflow-module-dag -am package` 产物含 `DagFacade`/`SubmitImagePlanHandler` bean
- 端到端冒烟 11 项人工验收全部通过

## Idempotence and Recovery

- 所有 mvn 命令可重复运行（idempotent）：创建文件用 `mkdir -p` 等价物（PowerShell `New-Item -ItemType Directory -Force`）；schema 资源文件覆盖写不破坏旧内容。
- 单测失败时定位到具体规则类或 executor，修复后 `mvn -pl pixflow-module-dag test -Dtest=具体类` 重跑。
- Testcontainers 集成测试若 Docker 不可用，按 `infra/storage` 已有的 profile 跳过策略（不视为失败）。
- 不需要任何数据迁移：dag 模块新建 `pending_plan` 表，不修改现有表。
- 回滚策略：移除 `pixflow-module-dag` 模块的 Maven 引用即可，不影响其他模块。

## Artifacts and Notes

### 关键代码片段参考

**CanonicalJson 规范化实现**（`src/main/java/com/pixflow/module/dag/ir/CanonicalJson.java`）：

```java
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(false));

    public static byte[] canonicalize(Object value) {
        try {
            JsonNode node = MAPPER.valueToTree(value);
            return MAPPER.writeValueAsBytes(canonicalize(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical JSON 序列化失败", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            // 字典序排序字段
            node.properties().stream()
                .map(Entry::getKey).sorted()
                .forEach(k -> sorted.set(k, canonicalize(node.get(k))));
            return sorted;
        }
        if (node.isArray()) return MAPPER.createArrayNode().addAll((ArrayNode) node);
        if (node.isNumber()) return normalizeNumber(node);
        return node;  // string/bool/null 原样
    }
}
```

**BranchExecutionContext 实现**（`src/main/java/com/pixflow/module/dag/exec/BranchExecutionContext.java`）：

```java
public final class BranchExecutionContext implements AutoCloseable {
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public void onDispose(RasterImage img) {
        cleanups.push(() -> { try { img.buffer().getGraphics().dispose(); } catch (Throwable ignored) {} });
    }
    public void onClose(InputStream s) {
        cleanups.push(() -> { try { s.close(); } catch (IOException ignored) {} });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            while (!cleanups.isEmpty()) safeRun(cleanups.pop());
        }
    }
}
```

**UnitOutcome 形态**（`src/main/java/com/pixflow/module/dag/exec/UnitOutcome.java`）：

```java
public record UnitOutcome(
    UnitKind kind, String branchId, String memberId,
    Status status, String outputObjectKey, String generatedCopy,
    List<MemberRef> members, DagErrorView error
) {
    public enum Status { SUCCEEDED, FAILED }
    public static UnitOutcome succeeded(UnitKind kind, String branchId, String memberId,
                                         String outputKey, List<MemberRef> members) {
        return new UnitOutcome(kind, branchId, memberId, Status.SUCCEEDED,
            outputKey, null, members, null);
    }
    public static UnitOutcome failed(UnitKind kind, String branchId, String memberId, DagErrorView error) {
        return new UnitOutcome(kind, branchId, memberId, Status.FAILED,
            null, null, null, error);
    }
}
```

### 关键测试片段参考

**branchId 确定性测试**（`BranchExpanderTest.java`）：

```java
@Test
void branchId_isStable_acrossFieldReordering() {
    ValidatedDag dag1 = parse("{\"nodes\":[{\"id\":\"n1\",\"tool\":\"resize\",\"params\":{\"width\":800,\"height\":600}}]}");
    ValidatedDag dag2 = parse("{\"nodes\":[{\"id\":\"n1\",\"tool\":\"resize\",\"params\":{\"height\":600,\"width\":800}}]}");

    String branchId1 = expander.expand(dag1, List.of()).get(0).branchId();
    String branchId2 = expander.expand(dag2, List.of()).get(0).branchId();

    assertThat(branchId1).isEqualTo(branchId2);
}
```

**失败隔离边界测试**（`PipelineUnitExecutorTest.java`）：

```java
@Test
void execute_returnsFAILED_andNeverThrows_onImageProcessingException() {
    when(imageCodec.decode(any())).thenThrow(new ImageProcessingException(
        Reason.CORRUPTED_IMAGE, ImageFormat.JPEG, 0, 0));

    UnitOutcome outcome = executor.execute(branch, input);

    assertThat(outcome.status()).isEqualTo(Status.FAILED);
    assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_UNIT_EXECUTION_FAILED);
    // 断言无异常抛出（如果抛出会导致测试失败）
}
```

### 依赖清单（pixflow-module-dag/pom.xml）

```xml
<dependencies>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-common</artifactId></dependency>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-image</artifactId></dependency>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-thirdparty</artifactId></dependency>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-ai</artifactId></dependency>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-storage</artifactId></dependency>
    <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-harness-state</artifactId></dependency>
    <dependency><groupId>com.networknt</groupId><artifactId>json-schema-validator</artifactId><version>1.5.3</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
    <dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId></dependency>
    <!-- 测试 -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers</artifactId><scope>test</scope></dependency>
</dependencies>
```

## Interfaces and Dependencies

### 与上游模块的契约

| 上游模块 | 契约 |
|---|---|
| `harness/tools` | `SubmitImagePlanHandler` 实现 `ToolHandler`，贡献 `ToolDescriptor` bean；dag 入参 `dag` 字段在 tools 层仅做浅层 JSON 校验，深度校验在 dag handler 内 |
| `module/task` | 调 `BranchExpander.expand(ValidatedDag, List<ImageDescriptor>)` 展开；调 `UnitExecutor.execute(ExecutableBranch, UnitInput)` 逐单元执行；喂入中立 `ImageDescriptor`/`CopyContext`；据 `UnitOutcome` 写 `process_result` |
| `module/conversation` | 确认 REST 边界调 `DagValidator.validate` 重校验 + `GroupPreflight.preflight` 算 expected_count 差异；编排令牌（permission）与 HITL；创建 `process_task` |

### 与下游模块的契约

| 下游模块 | 契约 |
|---|---|
| `infra/image` | 调 `ImageCodec.decode/encode`、`ImagePipeline.run`（逐图）、`runComposed`（组），传类型化 spec；字节 I/O 由 dag 在两端缝合 |
| `infra/thirdparty` | dag 是 `BackgroundRemovalClient.remove` 的唯一直接消费方 |
| `infra/ai` | `generate_copy` 调 `ChatModelClient.generateCopy` |
| `infra/storage` | 两端缝合：`getStream` 取源图、`put` 写结果（用 `StorageKeys.result/groupResult`） |
| `harness/state` | 中间产物引用经 `RunStateRefStore`（仅组支路）；`TaskAssetCache` 监听 task 终态事件 |
| `common` | 单元失败经 `ErrorNormalizer` 归一化；`DagErrorCode implements ErrorCode`；文案经 `Sanitizer` |

### 关键 SPI 形态

**UnitExecutor**（`com.pixflow.module.dag.exec.UnitExecutor`）：

```java
public interface UnitExecutor {
    UnitOutcome execute(ExecutableBranch branch, UnitInput input);
}
```

**BranchExpander**（`com.pixflow.module.dag.expand.BranchExpander`）：

```java
public interface BranchExpander {
    List<ExecutableBranch> expand(ValidatedDag dag, List<ImageDescriptor> images);
}
```

**GroupPreflight**（`com.pixflow.module.dag.expand.GroupPreflight`）：

```java
public interface GroupPreflight {
    List<PreflightDifference> preflight(ValidatedDag dag, Map<String, Integer> actualGroupCounts);
}
```

**DagFacade**（`com.pixflow.module.dag.DagFacade`，统一对外门面）：

```java
public interface DagFacade {
    DagValidationResult validate(DagDocument doc);
    PendingPlan proposeImagePlan(String conversationId, DagDocument doc, String note);
    List<ExecutableBranch> expand(ValidatedDag dag, List<ImageDescriptor> images);
    UnitOutcome executeUnit(ExecutableBranch branch, UnitInput input);
    List<PreflightDifference> preflightGroups(ValidatedDag dag, Map<String, Integer> actualGroupCounts);
}
```

### 关键配置

```yaml
pixflow:
  dag:
    validate:
      max-nodes: 50
      min-nodes: 1
    pending-plan:
      ttl: 30m
      cleanup-cron: "0 */5 * * * *"
    group-cache:
      ref-ttl: 2h
    execution:
      unit-timeout: 60s
      per-node-timeout: 30s
      copy-timeout: 30s
      source-bytes-limit: 209715200    # 200MB
    asset-cache:
      max-entries-per-task: 5
      enabled: true
```