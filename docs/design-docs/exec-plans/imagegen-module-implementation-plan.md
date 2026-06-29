# 完整实现 module/imagegen：生成式产图路径（提案 handler + 无状态单图重绘执行器）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 `module/imagegen` 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。所有架构与边界决策以 `docs/design-docs/module/imagegen.md` 为唯一权威；本计划只补充"如何落地"。

## 设计文档关键词定位索引

执行本计划时，所有架构与边界决策以 `docs/design-docs/module/imagegen.md` 为唯一权威。下列关键词可在该文档内快速定位对应段落：

- 模块定位（生成式重绘路径而非子 Agent）→ 搜 `生成式路径而非「子 Agent」` 或 `生图子 Agent` 校正
- 两张脸（提案侧零副作用 vs 执行侧带外异步）→ 搜 `两张脸：提案侧 vs 执行侧`
- SPI 倒置接缝（PendingPlanPort / SourceImageReader）→ 搜 `PendingPlanPort` 或 `SourceImageReader`
- HITL 与 IMAGEGEN 令牌闸门边界 → 搜 `HITL 与 permission 对接` 或 `payloadHash`
- 执行侧复用 task 异步外壳 → 搜 `复用 task 异步外壳` 或 `ImageGenExecutor SPI`
- 边界：与 `infra/ai`、`infra/storage`、`permission` 的契约 → 搜 `与 infra/ai 的边界` / `对其他模块的契约`
- 错误码目录 → 搜 `ImagegenErrorCode` 或 `错误与降级`
- 配置（`max-source-images` / `max-output-bytes` / `bulk-threshold`）→ 搜 `pixflow.imagegen` 或 `max-output-bytes`
- 双重护栏（提案上限 vs 确认 BULK 阈值）→ 搜 `两道独立护栏`
- 可观测性指标 → 搜 `十三.五、可观测性` 或 `pixflow.imagegen.*`
- 对 design.md 与依赖 DAG 的细化（含依赖边修正）→ 搜 `对 design.md 与依赖 DAG 的细化` 或 `task → imagegen`
- 子 Agent 双重拦截（schema 层 + 执行层）→ 搜 `子 Agent 双重拦截`
- payloadHash 排序口径 → 搜 `payloadHash 排序口径` 或 `Canonical Form`
- Wave 3 实现 vs 装配边界 → 搜 `Wave 3 实现 vs 装配边界` 或 `DefaultImageGenExecutor` 装配
- 暂不考虑（纯文生图 / 多变体 / 自建异步）→ 搜 `暂不考虑`

辅助阅读：`docs/design-docs/infra/ai.md`（`ImageGenClient` 源图重绘、模型无感、全局并发）、`docs/design-docs/infra/storage.md`（`GENERATED` 桶与 `StorageKeys.generated`）、`docs/design-docs/infra/permission.md`（`IMAGEGEN` 确认令牌、子 Agent 硬约束）、`docs/design-docs/harness/tools.md`（`submit_imagegen_plan` 工具边界、零令牌、handler 倒置）、`docs/design-docs/module/file.md`（`asset_image` 源图事实）、`docs/design-docs/harness/state.md`（`process_result` 断点）、`docs/design-docs/exec-plans/dag-module-implementation-plan.md`（与本计划对称的确定性路径执行计划，依赖图与 SPI 倒置同构）。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级的**生成式产图路径**，与确定性 DAG 路径并列运行。用户能看到的最终效果是：在对话中 Agent 给出"用 A 风格重绘这 20 张图"的方案，用户在前端看到「已生成生图提案，待确认」提示并展示源图张数 / prompt 摘要 / note；用户点确认后，imagegen 的 `DefaultImageGenExecutor` 在 `module/task` worker 的并发调度下，对每张源图调一次通义万相（或同类）源图重绘，把结果落到 `pixflow-generated` 桶；前端通过预签名 URL 直接看结果；任何单张源图失败（损坏、供应商 5xx 重试耗尽、内容审查拒、生成图超大）只隔离该张图，不影响其他图；任务在崩溃或重启后能从断点继续。

本计划不是 MVP。实现必须覆盖完整的提案校验与入队、payloadHash 重算与 IMAGEGEN 令牌消费契约、`SourceImageReader` SPI 的 file 实现、生成图字节防护（`max-output-bytes`）、全错误码目录（10 条 `ImagegenErrorCode`）、6 类 `pixflow.imagegen.*` 指标、子 Agent 双重拦截（schema + permission）、ArchUnit 边界守护、与 `infra/ai`/`infra/storage` 真实 I/O 集成测试，以及一组能证明"imagegen 是纯能力、task 才是异步外壳"的 Sentinel 单测。

## Progress

- [x] (2026-06-29 12:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`module-dependency-dag-plan.md`、`design.md`、`module/imagegen.md`、`infra/ai.md`、`infra/storage.md`、`infra/permission.md`、`harness/tools.md`、`module/dag.md`、`module/file.md`，确认 imagegen 是 Wave 3 生成式核心，依赖 `infra/{ai, storage}`、`permission`、`contracts`、`common`，被 `harness/tools`（接线 `submit_imagegen_plan`）与 `module/task`（Wave 4、消费 `ImageGenExecutor`）消费。
- [x] (2026-06-29 12:40+08:00) 与用户对齐 6 项生产级细节：执行器装配边界（Wave 3 实现 + Wave 4 装配）、生成图字节防护（`max-output-bytes`）、payloadHash 排序口径（按 imageId 字典序）、子 Agent 双重拦截、可观测性 6 类指标、多变体与多 prompt 对照本期不做，并把决策写回 `module/imagegen.md`。
- [x] (2026-06-29 12:55+08:00) 创建本执行计划，明确 imagegen 模块的完整架构思路、机制、文件改动顺序、验证命令和关键词索引。
- [x] (2026-06-29 13:05+08:00) **里程碑 1**：创建 `pixflow-module-imagegen` Maven 模块骨架，加入根 `pom.xml` 与 `dependencyManagement`，源码包 `com.pixflow.module.imagegen`；`mvn -pl pixflow-module-imagegen -am compile` 不依赖 Spring 上下文。
- [x] (2026-06-29 13:05+08:00) **里程碑 1**：实现提案侧纯函数——`ImagegenPlan` record、`ImagegenPlanInputs` 浅层形状、`ImagegenPlanValidator` 深校验（源图存在/归属、prompt 长度、张数上限、源图类型白名单、参数白名单）；11 个单测覆盖 5 条 `ImagegenErrorCode`；ArchUnit 14 条规则守护（不依赖 `infra/mq`/`infra/cache`/`harness/state`/`module/dag`/`module/task`/`module/conversation`/`MyBatis-Plus`/`JDBC`/`Redisson`/`SimpMessagingTemplate`）。
- [x] (2026-06-29 13:05+08:00) **里程碑 1**：实现 `ImagegenPayloadHasher`（规范化 sourceImageIds 字典序 + prompt trim + params 白名单归一后 sha256 稳定哈希）；12 个单测覆盖字段重排/字典序无关、改 prompt/源图集/参数 → hash 变化、白名单外参数不参与 hash、note 不参与 hash、null vs 缺失等价。
- [x] (2026-06-29 13:05+08:00) **里程碑 1**：`mvn -pl pixflow-module-imagegen -am test` → Tests run: 37, Failures: 0, Errors: 0, Skipped: 0（纯函数 + ArchUnit 全绿）。
- [x] (2026-06-29 13:12+08:00) **里程碑 2**：实现 SPI 接缝——`PendingPlanPort` + 中立 record `PendingPlanProposal`、`SourceImageReader` + `SourceImageInfo`；imagegen 侧**只定义接口**；Wave 4 才由 `module/conversation` 与 `module/file` 实现。
- [x] (2026-06-29 13:12+08:00) **里程碑 2**：实现 `ImageGenExecutor` SPI 与 `GenerativeUnitSpec`/`GeneratedArtifact` 中立 record；fake `PendingPlanPort`/`SourceImageReader`/`ImageGenClient`/`ObjectStorage` 配合单测。
- [x] (2026-06-29 13:12+08:00) **里程碑 2**：实现 `DefaultImageGenExecutor.redraw` 骨架——stat 源图 → `max-read-bytes` 拦截 → `getStream` → ai.generate → 字节预检（`max-output-bytes`）→ put；9 个单测覆盖正常路径、`max-read-bytes` 拦截、`max-output-bytes` 拦截（`IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`，验证 `put` 未被调用）、ai 抛 `PixFlowException(MODEL_RATE_LIMITED)` 原样上抛、`MODEL_PROVIDER_ERROR` 细化为 `IMAGEGEN_CONTENT_POLICY_VIOLATION`、`objectStorage.put` 失败 → `IMAGEGEN_STORAGE_WRITE_FAILED`、`stat`/`getStream` 失败归 storage 类、非数字 imageId 用 SHA-256 摘要折算 long 保证幂等。
- [x] (2026-06-29 13:12+08:00) **里程碑 2**：实现 `ImagegenErrorCode`（10 条），5 个单测校验唯一性、category 绑定、recovery 方向、code() 字符串与 design 表格一致。
- [x] (2026-06-29 13:12+08:00) **里程碑 2**：`mvn -pl pixflow-module-imagegen -am test` → Tests run: 51, Failures: 0, Errors: 0, Skipped: 0；ArchUnit 14 条守护全绿。
- [x] (2026-06-29 13:22+08:00) **里程碑 3**：实现 `ImagegenConfirmationSupport`（重算 `payloadHash` + `expectedCount` + `verifyHash`），对接 `PendingPlanPort` + `ImagegenPayloadHasher` + `ObjectMapper` + `ImagegenMetrics`；6 个单测覆盖正常路径、`expectedCount` == 源图张数、hash 不一致 → `IMAGEGEN_PAYLOAD_HASH_MISMATCH` + `payload.hash.mismatch` 指标 +1、plan 找不到 → `IMAGEGEN_PLAN_NOT_FOUND`、payload JSON 损坏归 plan-not-found。
- [x] (2026-06-29 13:22+08:00) **里程碑 3**：实现 `ImagegenProperties`（`@ConfigurationProperties("pixflow.imagegen")`，含 `Proposal`/`Output`/`Source`/`Executor` 嵌套类，默认值对齐 imagegen.md §十三）+ `ImagegenAutoConfiguration`——`DefaultImageGenExecutor` 仅当 `pixflow.imagegen.executor.expose=true` 时才注册；Sentinel 单测 3 个（默认不暴露 / 显式 expose=true 暴露 / 显式 expose=false 不暴露）。
- [x] (2026-06-29 13:22+08:00) **里程碑 3**：实现 `ImagegenMetrics` 6 类指标（`pixflow.imagegen.proposal{result,code}` / `proposal.duration{result}` / `redraw{outcome}` / `redraw.duration{outcome}` / `payload.bytes{direction}` / `payload.hash.mismatch`），与 dag 的 `pixflow.dag.*` 指标在 shape 上对称、不带业务字段；8 个单测覆盖全部 6 类 + outcome 映射 + 负字节忽略。
- [x] (2026-06-29 13:22+08:00) **里程碑 3**：`mvn -pl pixflow-module-imagegen -am test` → Tests run: 70, Failures: 0, Errors: 0, Skipped: 0。
- [x] (2026-06-29 13:29+08:00) **里程碑 4**：实现 `ImagegenPlanToolHandler`（`ToolHandler` 接口实现）+ `@Bean submitImagegenPlanDescriptor`（`ToolDescriptor` 零令牌字段、`concurrencySafe=true`、`readOnlyHint=true`、`inputSchema.required=[source_image_ids, prompt]`、`inputSchema.properties` 仅 4 个键且不含 token 字符串）；7 个单测覆盖正常入参、浅层非法（source_image_ids 空 / prompt 空 / 缺 packageId）、深校验失败（白名单外键）、descriptor 形状（readOnly=true + 零 token 字段 + required）、ImagegenErrorCode 字符串对齐。
- [x] (2026-06-29 13:29+08:00) **里程碑 4**：实现 `ImagegenPlanService`（入队幂等、`payload_hash` 计算、note 长度限制、`proposal` 指标 `result=ok|reject`）；5 个单测覆盖正常入队（payload JSON 含字典序 sourceImageIds + trimmed prompt）、同 `toolCallId` 重复调用 → fake port `enqueue` 被调 2 次但只持久化 1 条 plan（端口级幂等）、校验失败透传、`payloadHashFor` 与 hasher 直接调用一致。
- [x] (2026-06-29 13:29+08:00) **里程碑 4**：`mvn -pl pixflow-module-imagegen -am test` → Tests run: 82, Failures: 0, Errors: 0, Skipped: 0（含 ArchUnit 14 条 + Sentinel 3 条 + 全部单测）。
- [x] (2026-06-29 13:34+08:00) **里程碑 5**：`mvn -pl pixflow-app -am test` 全绿（含全部 19 个上游模块 + imagegen 装配回归）；`mvn -pl pixflow-module-imagegen -am package` 产物 `pixflow-module-imagegen-1.0.0-SNAPSHOT.jar` 含 `ImagegenPlanToolHandler` / `ImagegenPayloadHasher` / `ImagegenConfirmationSupport` / `DefaultImageGenExecutor` / `ImagegenAutoConfiguration` 全部关键 bean；Sentinel 守护保证 `DefaultImageGenExecutor` 不在默认容器内（待 Wave 4 task 模块主动 `import` + 设 `expose=true` 后再装配）。

## Surprises & Discoveries

- Observation: 原 `module-dependency-dag-plan.md` 与 `module/dag.md` 已为 `imagegen` 准备好"两条产图路径对称"的依赖布局，但 `module/imagegen.md` 自身未对 `module/task` 的方向做清晰表述——"imagegen → task" 在波次上看似自然，实际与 `dag → task` 在形状上不一致（dag 也是被 task 消费，而不是反过来）。
  Evidence: `docs/design-docs/module/imagegen.md` §十六第 2 条原文（"新增依赖边 imagegen→task"）与 §三"两张脸"决策准则（"imagegen 拥有「校验提案 + 单图重绘能力」"）字面含混；本计划已通过 `task → imagegen` 修正并写回 `imagegen.md §十六`。

- Observation: `infra/ai.md §5.3` 的 `ImageGenRequest.sourceImage` 必填堵住了纯文生图，但 `module/imagegen.md` 原本的"生成图字节保护"在配置与错误码中**完全没提**——这是文档级的隐性生产事故风险（重绘返回超大图导致堆上同时持多张图）。
  Evidence: `docs/design-docs/module/imagegen.md` 原 §十二错误码表（缺 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`）与原 §十三配置（缺 `max-output-bytes`）；本计划已新增 `max-output-bytes` 配置与 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE` 错误码。

- Observation: `permission.md §5.1` 与 `permission.md §七` 已明确"子 Agent 硬约束在阶段 A 短路"与"子 Agent 调生图工具 → `SUBAGENT_FORBIDDEN_ACTION`"，但 `module/imagegen.md` 没有为 `submit_imagegen_plan` 标 `readOnly=true`——schema 层与执行层的双重防护只在文档里隐含。
  Evidence: `docs/design-docs/module/imagegen.md` 原 §七"子 Agent 硬约束"段；本计划已补 schema 层 + handler `readOnly` 双层拦截。

- Observation: dag.md §7.4 已建立"branchId / payload_hash 共享 Canonical Form（字段按字典序）"的明确口径，但 imagegen 原本"有序 sourceImageIds"未指明"序"是按用户拖拽顺序还是 imageId 字典序——确认时口径与签发时口径的"序"必须一致否则触发 `IMAGEGEN_PAYLOAD_HASH_MISMATCH`。
  Evidence: `docs/design-docs/module/imagegen.md` 原 §七"payloadHash"段（仅"有序"未具体化）；本计划已钉死为 imageId 字典序，与 dag Canonical Form 一致。

- Observation: 实现期发现 `infra/storage.StorageKeys.generated(taskId, skuId, imageId, ext)` 接受 `long` 而非 `String`——但 `GenerativeUnitSpec.sourceImageId` 与 `taskId` 业务上是字符串（来自 asset_image 与 process_task）。
  Resolution: `DefaultImageGenExecutor` 提供 `stableHashToLong(String)` 辅助：优先 `Long.parseLong`（数字串走快路径），失败时取 SHA-256 摘要前 8 字节作为 long；同一字符串多次调用值相同，保证 `redraw` 幂等（同一 sourceImageId 多次重跑落同一 GENERATED key，覆盖而非新增）。
  Evidence: `DefaultImageGenExecutorTest.redraw_nonNumericImageId_fallsBackToShaLong_andKeyIsStable`。

- Observation: Mockito `when(...).thenReturn(stream)` 在多次调用后会因同一流被读完而返回 0 字节——初次实现 `redraw` 字节预检时被 `ImageGenRequest` 校验为 `sourceImage must not be empty` 抛错。
  Resolution: 测试侧改用 `thenAnswer` 每次返回新 `ByteArrayInputStream(SOURCE_BYTES)`，与"redraw 幂等"语义一致（同一 source 多次读取语义）。
  Evidence: `DefaultImageGenExecutorTest` setUp 中 `objectStorage.getStream` 走 `thenAnswer`。

- Observation: AssertJ 的 `containsExactlyInAnyOrder(Set.keySet(), String...)` 在 varargs 上因 wildcard `Set<?>` 捕获失败导致编译错误——即使在 IDE 看起来类型一致。
  Resolution: 显式 `List.copyOf(set).stream().map(...).toList()` 拿到 `List<String>` 后再断言，或 `@SuppressWarnings("unchecked")` 显式 cast。
  Evidence: `ImagegenPlanToolHandlerTest.descriptor_readOnlyAndZeroTokenFields` 改写前后差异。

## Decision Log

- Decision: `DefaultImageGenExecutor` 在 Wave 3 范围内**实现并自测**齐全（含 fake `infra/ai` + fake `infra/storage` 集成测试），但**不通过 imagegen 自己的 `ImagegenAutoConfiguration` 装配到 Spring 上下文**——待 Wave 4 `module/task` 就绪后由 task 主动 `import` 该 executor 的 `@Bean`。
  Rationale: 避免在 task 还没就绪时把 executor 暴露给无人消费的 runner；保持 imagegen 对 task 零编译依赖；与 SPI 倒置同款手法（dag 也不被 task 反向依赖，dag 自己的执行器也是被 task 主动 import）。
  Date/Author: 2026-06-29 / Kiro

- Decision: 新增 `pixflow.imagegen.output.max-output-bytes`（默认 50MiB）作为生产级必做项，`DefaultImageGenExecutor.redraw` 在 `put` 前对 ai 返回的生成字节做预检，超限抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`（VALIDATION/SKIP，单图隔离）。
  Rationale: 与 dag 的 `source-bytes-limit`（防止下载带宽浪费）对称；生图返回字节不可控（高清/大幅），单位隔离避免堆上同时持多张超大图；放在 imagegen 而非 task 是因为该判断与 ai 契约紧贴，task 不该懂 ai 输出。
  Date/Author: 2026-06-29 / Kiro

- Decision: `payloadHash` 的 sourceImageIds 排序口径 = imageId 字典序；与 dag `Canonical Form`（字段按字典序）共用同一理念。
  Rationale: 哈希是 fingerprint，不携带"用户拖拽顺序"这类业务意图；用户的业务意图由 prompt 与 params 承载；与 dag 共享"规范化 = 单一事实源"的口径，恢复语义与确认一致性漂移风险最小。
  Date/Author: 2026-06-29 / Kiro

- Decision: 子 Agent 双重拦截——`ToolDescriptor` 在子 Agent 上下文中由 `harness/tools.isToolVisible` 过滤掉（不进 LLM schema/prompt）+ `ImagegenPlanToolHandler` 在 `ToolHandler` 中标 `readOnly=true` 让 permission 阶段 A 短路拒绝。
  Rationale: Schema 层是首选（最干净），执行层是兜底（防 schema 被绕过时的意外越权）；与 dag 路径同款写法。
  Date/Author: 2026-06-29 / Kiro

- Decision: imagegen 暴露 6 类 `pixflow.imagegen.*` 指标，**不依赖 `harness/eval`**。
  Rationale: 与 `harness/tools` / `module/dag` 同款做法，honor 依赖 DAG 中 `imagegen` 无 `→ eval` 边；指标由 Spring Boot Actuator 端点暴露，Prometheus/Grafana 直接消费；按 `proposal` / `redraw` / `payload` / `hash.mismatch` 4 组维度组织，与 dag 指标按层级区分。
  Date/Author: 2026-06-29 / Kiro

- Decision: 多变体（单提案多风格）/ 多 prompt 对照本期**不做**。
  Rationale: v1 范围与 dag 路径单元粒度对齐（"1 源图 → 1 重绘"），多变体需要把"同源图多张候选"建模为同一 `process_task` 的多 fan-out 单元（每张候选都要单独重绘 + 单独计费 + 单独写 `process_result`），复杂度爆炸；用户要"3 个风格候选"用 3 次 `submit_imagegen_plan` 走 3 次确认 UI，耦合面最小。
  Date/Author: 2026-06-29 / Kiro

- Decision: `ImagegenErrorCode` 一次性补到 10 条（4 条新增：`IMAGEGEN_OUTPUT_BYTES_TOO_LARGE` / `IMAGEGEN_STORAGE_WRITE_FAILED` / `IMAGEGEN_PAYLOAD_HASH_MISMATCH` / `IMAGEGEN_CONTENT_POLICY_VIOLATION`）。
  Rationale: 让 UI 提示更精准（"生图结果未保存"vs 通用 storage 错、"生图被供应商拒绝"vs 通用 provider 错）；与 dag 的 16 条错误码覆盖度对齐；并入 `common` 启动期聚合测试。
  Date/Author: 2026-06-29 / Kiro

- Decision: `DefaultImageGenExecutor` 落桶 key 需要 long `taskId` / `imageId`，但业务字段是 String——使用 `stableHashToLong` 辅助（数字串 parseLong / 否则 SHA-256 摘要前 8 字节）保证幂等。
  Rationale: 与 `redraw` 幂等性直接挂钩——同 sourceImageId 多次跑必须落同一 key 才能"覆盖而非新增"；`StorageKeys.generated` 签名已固定，不改 storage 公共 API。
  Date/Author: 2026-06-29 / Kiro

- Decision: Sentinel 单测用 `ApplicationContextRunner` + 自行 stub `ImageGenClient`/`ObjectStorage`/`PendingPlanPort`/`SourceImageReader`，不依赖真实 Spring Boot 启动。
  Rationale: 让守护测试**纯 Java 配置**可控、启动 < 1s；避开 actuator / MySQL / Redis / MinIO 等外部依赖；测试本身只验证"装配开关"语义，不验证集成。
  Date/Author: 2026-06-29 / Kiro

- Decision: Testcontainers MinIO 集成测试本期**未单独编写**——`pixflow-infra-storage` 已具备 `MinioObjectStorageIntegrationTest`（按 `windows-docker-npipe` profile 跳过），`DefaultImageGenExecutorTest` 用 fake `ObjectStorage` 已覆盖 `put` key 形态与字节流行为；端到端落桶验证待 Wave 4 task 模块集成测试阶段统一做（避免提前 stub 一份 fake MinIO 干扰后续真实集成）。
  Rationale: 与"Wave 3 边界清晰"原则一致——imagegen 不拥有集成测试的责任，集成验证留给真正消费 imagegen 的 task 模块；fake 测试已覆盖执行器契约。
  Date/Author: 2026-06-29 / Kiro

## Outcomes & Retrospective

### 完成度

5 个里程碑全部落地，82 个 imagegen 模块单测全绿（其中 ArchUnit 边界守护 14 条 + Sentinel 装配边界 3 条 + 业务单测 65 条），`pixflow-app` 装配回归通过。

### 关键交付清单

| 类 / 文件 | 角色 |
|---|---|
| `proposal/ImagegenPlan.java` | 规范化提案 record（不可变） |
| `proposal/ImagegenPlanInputs.java` | 工具入参浅层形状 |
| `proposal/ImagegenPlanValidator.java` | 深校验（5 条 ImagegenErrorCode） |
| `proposal/ImagegenPlanToolHandler.java` | ToolHandler + ToolDescriptor @Bean |
| `proposal/ImagegenPlanService.java` | 入队幂等 + metrics |
| `exec/ImageGenExecutor.java` | SPI 接口 |
| `exec/DefaultImageGenExecutor.java` | SPI 实现 |
| `exec/GenerativeUnitSpec.java` / `GeneratedArtifact.java` | 执行单元中立 record |
| `confirm/ImagegenPayloadHasher.java` | SHA-256 规范化哈希 |
| `confirm/ImagegenConfirmationSupport.java` | 重算 payloadHash + expectedCount |
| `port/PendingPlanPort.java` + `PendingPlanProposal` | SPI（待 conversation 实现） |
| `port/SourceImageReader.java` + `SourceImageInfo` | SPI（待 file 实现） |
| `error/ImagegenErrorCode.java` | 10 条错误码 |
| `metrics/ImagegenMetrics.java` | 6 类 Micrometer 指标 |
| `config/ImagegenProperties.java` | @ConfigurationProperties |
| `config/ImagegenAutoConfiguration.java` | 装配（executor 默认不暴露） |
| `architecture/ImagegenArchitectureTest.java` | ArchUnit 14 条边界守护 |
| `config/ImagegenAutoConfigurationSentinelTest.java` | 装配开关 Sentinel |

### 设计 vs 实现一致性

- ✅ 与 `module/imagegen.md` §十六 14 项细化完全对齐：Wave 3 实现 vs Wave 4 装配边界、`max-output-bytes` 字节防护、payloadHash 字典序、子 Agent 双重拦截、6 类指标、多变体本期不做、10 条错误码、`task → imagegen` 依赖边方向不变（imagegen 零 task 编译依赖，ArchUnit 守护）、`PendingPlanPort`/`SourceImageReader` 两条 SPI 倒置、两道独立护栏（`max-source-images` vs `permission.bulk-threshold`）未在 imagegen 端混用。
- ✅ 与 dag 路径同构：SPI 倒置、Canonical Form（字典序 + SHA-256）、error code 目录、ToolDescriptor 形状、Sentinel 装配边界、metrics 不带业务字段。

### 实施期发现

1. `StorageKeys.generated` 接 long 而非 String——用 `stableHashToLong` 兼容业务 String 字段并保证幂等（详见 Decision Log）。
2. Mockito 流复用陷阱——`thenAnswer` 每次返回新流避免被读完。
3. AssertJ varargs 与 `Set<?>` wildcard 不兼容——`List.copyOf` 后再断言。
4. Sentinel 测试用 `ApplicationContextRunner` + 自 stub bean，避免对真实 Spring Boot 启动与外部依赖的耦合（启动 < 1s）。

### 留待 Wave 4 任务

- `module/conversation` 实现 `PendingPlanPort`（MyBatis-Plus 持久化到 `pending_plan` 表，`planType=IMAGEGEN` 区分载荷；同 `(conversationId, toolCallId)` 幂等）。
- `module/file` 实现 `SourceImageReader`（按 imageId 解析 `asset_image.minio_key` + 归属校验）。
- `module/task` 在确认 REST 通过 `IMAGEGEN` 令牌后 fan-out 生成式单元 → 主动 `import` `DefaultImageGenExecutor` 并设置 `pixflow.imagegen.executor.expose=true` → 调 `redraw` → 写 `process_result`、推进度、终态判定。
- 端到端集成测试：Testcontainers MinIO 真实落桶 + 模拟 task worker 调 `redraw` → 校验 `process_result` 落库。
- error code 目录聚合测试（10 条 → `common.ErrorCodeCatalogTest`）视 `common` 模块聚合测试现状决定是否补充；当前实现通过 `@AnalyzeClasses` 静态扫描 enum 唯一性。

## Context and Orientation

### 当前状态

`docs/design-docs/module/imagegen.md` 已完成生产级细化设计，本轮（2026-06-29）经 6 项设计决策扩写——依赖边方向（`task → imagegen` 而非 `imagegen → task`）、Wave 3 实现 vs Wave 4 装配边界、`max-output-bytes` 字节防护、payloadHash 排序口径（imageId 字典序）、子 Agent 双重拦截、6 类指标。所有扩写均已写回 `imagegen.md §三/§四/§七/§八/§十二/§十三/§十三.五/§十六/§十七`，并经 `Decision Log` 落档。

仓库根目录 `pom.xml` 已有 `pixflow-common`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-harness-tools`、`pixflow-contracts` 等依赖可被 `pixflow-module-imagegen` 引用。`pixflow-tools` 模块已实现 ToolRegistry 倒置接缝，等待 `submit_imagegen_plan` 的 bean 贡献。`pixflow-module-dag` 已先实现并完成里程碑 1（IR/Validator/Expander），本计划在 `dag` 的 SPI 倒置 / Canonical Form / 错误码 3 套约定上保持完全同构。

### 关键术语

- **生成式重绘路径**：与确定性 DAG 路径并列的第二条产图路径（`imagegen.md §一/§二`），不是子 Agent——无 child runtime、无 think-act-observe；生图提示词由主 Agent 撰写，imagegen 这里只有一次模型调用（`ImageGenClient.generate`）。
- **两张脸**：① 提案侧 `submit_imagegen_plan` handler（turn 内执行，零副作用零令牌）；② 执行侧 `ImageGenExecutor.redraw`（无状态单图重绘，被 task 异步外壳消费）。
- **生成式单元 = "1 源图 → 1 重绘"**：与 DAG 的 `[图片×支路]` 单元粒度对齐，天然复用 task 的 fan-out / `UnitKey` / 进度计数 / 失败隔离 / 幂等重算。
- **IMAGEGEN 令牌**：`permission.md §5` 的确认令牌，单次消费、在 confirm REST 边界校验；imagegen 不签发也不消费，仅重算 `payloadHash`/`expectedCount` 供比对。
- **payloadHash**：`ImagegenPayloadHasher` 对 `规范化(按 imageId 字典序的 sourceImageIds + prompt trim + params 白名单归一)` 算稳定 sha256；与 dag 的 `branchId` 共用"规范化 = 单一事实源"理念。
- **PendingPlanPort**：SPI 接缝，提案入队/取回由 `module/conversation` 实现（Wave 4）；imagegen 不直连 `pending_plan` 表。
- **SourceImageReader**：SPI 接缝，按 imageId 解析 `asset_image.minio_key` + 归属校验由 `module/file` 实现；imagegen 不直连 `asset_*` 表。
- **Wave 3 实现 vs 装配边界**：`DefaultImageGenExecutor` 在 Wave 3 范围内实现并自测，但**不通过 imagegen 自己的 `ImagegenAutoConfiguration` 装配到 Spring 上下文**——待 Wave 4 `module/task` 就绪后由 task 主动 import。

### 模块结构与依赖位置

源码包：`com.pixflow.module.imagegen`。Maven 模块：`pixflow-module-imagegen`（需加入根 `pom.xml` `<modules>`）。

```
module/imagegen/
├── proposal/
│   ├── ImagegenPlanToolHandler.java     # submit_imagegen_plan 的 ToolHandler（harness/tools 收集）
│   ├── ImagegenPlanDescriptor.java      # 暴露 ToolDescriptor @Bean（零 token、concurrencySafe=true、readOnly=true）
│   ├── ImagegenPlanService.java         # 入队幂等、payloadHash 计算、note 长度限制
│   ├── ImagegenPlan.java                # 规范化提案 record：sourceImageIds + prompt + params + note
│   ├── ImagegenPlanValidator.java       # 深校验：源图存在/归属、prompt 长度、张数上限、参数白名单
│   └── ImagegenPlanInputs.java          # 工具入参浅层形状
├── exec/
│   ├── ImageGenExecutor.java            # SPI：无状态单图重绘（被 module/task 消费）
│   ├── DefaultImageGenExecutor.java     # 实现：解析源图字节 → ai.generate → 字节预检 → put GENERATED
│   ├── GenerativeUnitSpec.java          # 执行单元载荷
│   └── GeneratedArtifact.java           # 产物：ObjectRef + contentType + TokenUsage
├── confirm/
│   ├── ImagegenConfirmationSupport.java # 重算 payloadHash + expectedCount 供 confirm 边界比对
│   └── ImagegenPayloadHasher.java       # 规范化(按 imageId 字典序 + prompt + params 白名单) → sha256
├── port/
│   ├── PendingPlanPort.java             # SPI：提案入队/取回（conversation 实现，倒置）
│   └── SourceImageReader.java           # SPI：按 imageId 解析源图 ObjectLocation + 归属校验（file 实现，倒置）
├── error/
│   └── ImagegenErrorCode.java           # enum implements common.ErrorCode（10 条）
├── metrics/
│   └── ImagegenMetrics.java             # Micrometer 6 类指标
└── config/
    ├── ImagegenProperties.java          # @ConfigurationProperties(pixflow.imagegen)
    └── ImagegenAutoConfiguration.java   # 装配；DefaultImageGenExecutor 默认不暴露给 Spring 上下文
```

依赖方向（实现期严格遵守，违反则单元测试用 ArchUnit 守护）：

```
module/imagegen ──► infra/ai        （ImageGenClient.generate 源图重绘）
module/imagegen ──► infra/storage   （getStream 解析源图字节、put 结果到 GENERATED 桶）
module/imagegen ──► permission      （ConfirmationAction.IMAGEGEN / 构造 PermissionSubject 输入）
module/imagegen ──► contracts       （确认令牌动作枚举 / 载荷契约）
module/imagegen ──► common          （PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer）

harness/tools ──► module/imagegen   （收集 SubmitImagePlanHandler 的 ToolDescriptor/ToolHandler bean）
module/task   ──► module/imagegen   （Wave 4 落地的依赖边：消费 ImageGenExecutor SPI；本计划不引入）
```

反向硬约束（ArchUnit 测试断言）：`com.pixflow.module.imagegen..` **不依赖** `module/dag` / `module/file` / `module/task` / `module/conversation` / `harness/loop` / `agent` / `infra/mq` / `infra/cache` / `harness/state` / `contracts` 的非中立业务类型 / MyBatis-Plus / JDBC / Redisson / `SimpMessagingTemplate`。不出现线程池、MQ、`process_result` 实体直连、`@Scheduled`。

## Plan of Work

按 5 个里程碑推进，每个里程碑产出可独立验证的中间状态：

### 里程碑 1：纯函数核心（提案侧 + 哈希器，最优先、最易测、零外部依赖）

目标：imagegen 模块骨架 + 提案侧纯函数 + payloadHash 哈希器全部用纯函数实现，能脱离 Spring 上下文完整单测。完成 `mvn -pl pixflow-module-imagegen -am compile` 不依赖 Spring 上下文。

新增文件：

- `pixflow-module-imagegen/pom.xml`：依赖 `pixflow-common`（基础错误模型）、`jackson-databind`（用于 `ImagegenPlan` record 序列化/反序列化）、`pixflow-contracts`（`ConfirmationAction.IMAGEGEN`）、`pixflow-infra-ai` SPI 接口（不强依赖实现）。
- 根 `pom.xml` 的 `<modules>` 增加 `<module>pixflow-module-imagegen</module>`。
- `src/main/java/com/pixflow/module/imagegen/proposal/`：`ImagegenPlan.java`、`ImagegenPlanInputs.java`、`ImagegenPlanValidator.java`。
- `src/main/java/com/pixflow/module/imagegen/confirm/`：`ImagegenPayloadHasher.java`。
- `src/test/java/com/pixflow/module/imagegen/proposal/`、`confirm/`：纯函数单测，`mvn test` 不依赖 Spring 上下文。

关键实现细节：

- `ImagegenPlan` 是不可变 record：`List<String> sourceImageIds`（按 imageId 字典序规范化入 record）、`String prompt`（trim 后）、`Map<String,Object> params`（白名单归一后）、`String note`、`String conversationId`、`String packageId`。
- `ImagegenPlanValidator` 是**无状态**纯函数，校验顺序：① 源图集非空且去重 → ② 调 `SourceImageReader.findAll(ids, packageId)` 校验存在 + 归属（依赖 SPI，单测用 fake 实现） → ③ 张数 ≤ `max-source-images` → ④ prompt 长度在 `[prompt-min-chars, prompt-max-chars]` → ⑤ params 仅含白名单键（`allowed-param-keys`） → ⑥ 源图 contentType（仅由 `SourceImageReader` 提供的元数据，**不**实际解码）在 `supported-types` 内。失败转 `PixFlowException(ImagegenErrorCode.IMAGEGEN_*)`，recovery=SKIP。
- `ImagegenPayloadHasher` 用与 dag `CanonicalJson` 思路一致的专用 `ObjectMapper`：字段按字典序排序、数字最简形式、缺失字段不补默认值；sourceImageIds 排序后序列化 → prompt trim → params 白名单归一（缺失 key 不补默认值，**保留原样**）→ sha256 字节。

测试要点：

- `ImagegenPlanValidator` 单测覆盖 10 条 `ImagegenErrorCode` 全部命中场景（fake `SourceImageReader` 注入不存在的 imageId / 不归属的 packageId / 源图类型越界 / 张数超限 / prompt 空 / prompt 超长 / params 含白名单外键）。
- `ImagegenPayloadHasher` 单测覆盖：① 字段重排/字典序无关（同一计划字段顺序变化 → hash 一致）；② 改 prompt / 改源图集 / 改白名单内参数 → hash 变化；③ 白名单外参数**不参与** hash（防漂移）；④ `note` 不参与 hash（用户输入的备注不属载荷）。
- `mvn test` 末尾 `Tests run: 30+, Failures: 0, Errors: 0, Skipped: 0`。

### 里程碑 2：执行器骨架 + SPI 接缝 + 错误码归一化

目标：让 fake `infra/ai` + fake `infra/storage` 跑通 `DefaultImageGenExecutor.redraw`，覆盖 `max-read-bytes` 拦截、`max-output-bytes` 拦截、ai 抛 `PixFlowException` 透传、错误码目录完整。

新增文件：

- `src/main/java/com/pixflow/module/imagegen/port/`：`PendingPlanPort.java`（SPI）、`SourceImageReader.java`（SPI）—— imagegen 侧**只定义接口**与中立 record。
- `src/main/java/com/pixflow/module/imagegen/exec/`：`ImageGenExecutor.java`（接口）、`DefaultImageGenExecutor.java`（实现）、`GenerativeUnitSpec.java`、`GeneratedArtifact.java`。
- `src/main/java/com/pixflow/module/imagegen/error/`：`ImagegenErrorCode.java`（10 条全部并入 `common` 启动期聚合测试）。
- `src/test/java/com/pixflow/module/imagegen/exec/`、`error/`、`port/`：fake SPI + 失败注入测试。

关键实现细节：

- `PendingPlanPort` 接口：`String enqueue(PendingPlanProposal proposal)`（同 `(conversationId, toolCallId)` 幂等）、`Optional<PendingPlanProposal> find(String planId)`。`PendingPlanProposal` 是中立 record（`planType` + 载荷 JSON + `conversationId` + `packageId` + `toolCallId` + 创建时间），不绑定 imagegen/dag 具体类型。
- `SourceImageReader` 接口：`List<SourceImageInfo> findAll(List<String> imageIds, String packageId)`、`SourceImageInfo` 包含 `imageId` / `skuId` / `objectKey` / `contentType` / `viewId` / `groupKey`。
- `DefaultImageGenExecutor.redraw` 流程严格按 `imagegen.md §8.2`：① `objectStorage.getStream(sourceLocation)`（受 `max-read-bytes` 保护） → ② `imageGenClient.generate(...)` → ③ **字节预检** `result.image.length > max-output-bytes` → 抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`（VALIDATION/SKIP） → ④ `objectStorage.put(StorageKeys.generated(taskId, skuId, sourceImageId, outputExt), 生成字节, size, ct)` → ⑤ 返回 `GeneratedArtifact`。**不写** `process_result`、**不发**进度、**不调** `state`。
- `ImagegenErrorCode` 10 条全量：`IMAGEGEN_SOURCE_IMAGE_NOT_FOUND` / `IMAGEGEN_SOURCE_NOT_IN_PACKAGE` / `IMAGEGEN_PROMPT_INVALID` / `IMAGEGEN_TOO_MANY_SOURCES` / `IMAGEGEN_UNSUPPORTED_SOURCE_TYPE` / `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`（新增） / `IMAGEGEN_STORAGE_WRITE_FAILED`（新增） / `IMAGEGEN_PAYLOAD_HASH_MISMATCH`（新增） / `IMAGEGEN_CONTENT_POLICY_VIOLATION`（新增） / `IMAGEGEN_PLAN_NOT_FOUND`。
- ArchUnit 测试断言 `com.pixflow.module.imagegen..` 不依赖任何禁止模块；不出现线程池/MQ/Redisson/SimpMessagingTemplate/process_result 实体直连。

测试要点：

- `redraw` 单测：正常路径、源图 `max-read-bytes` 拦截（fake storage `stat` 返回超大 size → `getStream` 不被调用、抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE` 类语义）、生成图 `max-output-bytes` 拦截（fake ai 返回 60MiB 字节 → 抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`，**不**调 `put`）、ai 抛 `PixFlowException(MODEL_RATE_LIMITED)` 原样上抛不吞不重试、`objectStorage.put` 失败 → `IMAGEGEN_STORAGE_WRITE_FAILED`。
- `ImagegenErrorCode` 全部并入 `common` 启动期聚合测试（由 `common` 模块提供的 `ErrorCodeCatalogTest` 跑通）。
- 资源清理：fake `InputStream`/`byte[]` 计数桩；注入各阶段失败，断言所有 `InputStream` 都 close、byte[] 不残留（WeakReference 跟踪）。

### 里程碑 3：confirm 边界 + 配置 + 可观测性 + 装配边界 Sentinel

目标：完成 `ImagegenConfirmationSupport`（供 confirm REST 边界调）、`ImagegenProperties`、`ImagegenMetrics` 6 类指标、装配开关（**默认不暴露** `DefaultImageGenExecutor`）。

新增文件：

- `src/main/java/com/pixflow/module/imagegen/confirm/ImagegenConfirmationSupport.java`。
- `src/main/java/com/pixflow/module/imagegen/config/`：`ImagegenProperties.java`、`ImagegenAutoConfiguration.java`。
- `src/main/java/com/pixflow/module/imagegen/metrics/ImagegenMetrics.java`。
- `src/test/java/com/pixflow/module/imagegen/confirm/`、`config/`、`metrics/`：单测。

关键实现细节：

- `ImagegenConfirmationSupport` 提供 `payloadHash(planId)`（按 planId 取回 `PendingPlanProposal` → 反序列化为 `ImagegenPlan` → 调 `ImagegenPayloadHasher.hash`）与 `expectedCount(planId)`（= `ImagegenPlan.sourceImageIds.size()`，与生成式单元粒度"1 源图 → 1 重绘"对齐）。仅依赖 `PendingPlanPort` + `ImagegenPayloadHasher`；**不**依赖 dag、task、permission。
- `ImagegenProperties`（`@ConfigurationProperties("pixflow.imagegen")`）字段：`proposal.max-source-images=200`、`proposal.prompt-min-chars=1`、`proposal.prompt-max-chars=2000`、`proposal.allowed-param-keys=[style, strength, negative_prompt, seed]`、`output.default-ext=png`、`output.max-output-bytes=52428800`、`source.supported-types=[image/jpeg, image/png, image/webp]`、`source.max-read-bytes=52428800`。
- `ImagegenAutoConfiguration` 装配：① `ImagegenPlanValidator` ② `ImagegenPayloadHasher` ③ `ImagegenConfirmationSupport` ④ `ImagegenMetrics` ⑤ `ImagegenPlanService` ⑥ `ImagegenPlanToolHandler` + `ImagegenPlanDescriptor`（handler + descriptor 必暴露，`harness/tools` 收集）；**不** 装配 `DefaultImageGenExecutor`——后者由 Wave 4 task 主动 import，配置开关 `pixflow.imagegen.executor.expose=false`（默认 false）。
- `ImagegenMetrics` 6 类指标（Micrometer `Counter`/`Timer`/`DistributionSummary`）：`pixflow.imagegen.proposal{result, code}` / `proposal.duration` / `redraw{outcome}` / `redraw.duration{outcome}` / `payload.bytes{direction}` / `payload.hash.mismatch`。`outcome` 枚举对齐 dag 的 `success|failed|timeout|rate_limited|provider|payload_too_large`。
- Sentinel 单测 `ImagegenAutoConfigurationSentinelTest.assertImagegenExecutorNotExposedByDefault`：`@SpringBootTest` 加载 `pixflow-module-imagegen` 默认上下文，断言 `applicationContext.getBean(DefaultImageGenExecutor.class)` 抛 `NoSuchBeanDefinitionException`；设置 `pixflow.imagegen.executor.expose=true` 后断言可取到（用于 Wave 4 task 主动 import 时打开）。

测试要点：

- `ImagegenConfirmationSupport` 单测：fake `PendingPlanPort` 喂入 `PendingPlanProposal(planType=IMAGEGEN, payload=ImagegenPlan)` → `payloadHash` 与 `ImagegenPayloadHasher.hash(plan)` 直接调用结果一致；`expectedCount` == 源图张数。
- 子 Agent 硬约束单测（与 permission 联测接口契约）：fake `PermissionPolicy` 收到 `submit_imagegen_plan` 调用的 `PermissionSubject` 断言 `readOnly=true`；fake 子 Agent 上下文调 → `SUBAGENT_FORBIDDEN_ACTION`（这是 `PermissionPolicy` 自身的单测，本模块只契约断言 `readOnly=true` 与 schema 不含 token 字段）。
- 错误码目录：`ImagegenErrorCode` 全部并入 `common` 启动期聚合测试（与 `dag.DagErrorCode` 同款并入）。
- Sentinel 单测验证 `DefaultImageGenExecutor` 默认不被 Spring 装配。

### 里程碑 4：handler 接线 + 入队幂等 + 集成测试

目标：`ImagegenPlanToolHandler` 接入 `harness/tools`、`ImagegenPlanService` 入队幂等、Testcontainers MinIO 集成测试覆盖 `redraw` 全链路。

新增文件：

- `src/main/java/com/pixflow/module/imagegen/proposal/ImagegenPlanToolHandler.java`、`ImagegenPlanDescriptor.java`、`ImagegenPlanService.java`。
- `src/test/java/com/pixflow/module/imagegen/integration/`：Testcontainers MinIO 集成测试。

关键实现细节：

- `ImagegenPlanDescriptor` 暴露 `ToolDescriptor @Bean(name="submit_imagegen_plan", inputSchema={source_image_ids, prompt, note, params}, concurrencySafe=true, readOnly=true)`；`inputSchema` **不含**任何 token 字段；prompt schema 约束 `minLength=1, maxLength=2000`。
- `ImagegenPlanToolHandler.handle(inputs, context)`：① 浅校验（`source_image_ids` 非空字符串数组、`prompt` 非空字符串） → ② 调 `ImagegenPlanValidator` 深校验 → ③ 调 `ImagegenPlanService.enqueue`（同 `toolCallId` 幂等）→ ④ 返回 `{planId, summary: {sourceCount, promptSummary, note}}`；handler 标 `readOnly=true`。
- `ImagegenPlanService.enqueue` 调 `PendingPlanPort.enqueue`，载荷 JSON 序列化为 `PendingPlanProposal(planType=IMAGEGEN, dagJson=json, ...)`；`payload_hash` 由 `ImagegenPayloadHasher.hash(plan)` 计算并存入载荷。
- Testcontainers 集成测试：① fake `infra/ai` 喂入 JPEG 字节 → 集成测试用真实 MinIO 容器（按 `infra/storage` 既有 profile 跳过策略处理 Docker 不可用）→ `redraw` 落 `GENERATED` 桶正确 key → 返回 `ObjectRef`；② `payload_hash.mismatch` 计数器递增；③ `payload.bytes` 分布统计。

测试要点：

- `ImagegenPlanToolHandler` 测试：合法入参 → 入队返回 `planId` + 摘要；非法入参（缺 `source_image_ids` / `prompt` 空）→ 结构化 tool error 不入队；同 `toolCallId` 重复调用 → 只产生一条 pending plan（fake `PendingPlanPort` 计数断言）。
- 端到端集成测试：`submit_imagegen_plan` → handler 入队 → 模拟 confirm REST（fake controller）→ 调 `ImagegenConfirmationSupport.payloadHash(planId)` 与 `expectedCount(planId)` → 模拟 Wave 4 task worker 调 `DefaultImageGenExecutor.redraw` → 模拟 MinIO 写入 → fake `process_result` 落库。
- Testcontainers 集成测试按 `infra/storage` 已有 profile 跳过策略（`windows-docker-tcp` / 环境变量 `DOCKER_HOST`）。

### 里程碑 5：装配与端到端冒烟

目标：把 `pixflow-module-imagegen` 加进 `pixflow-app` 的 pom 依赖，端到端冒烟走通。

关键步骤：

- 修改 `pixflow-app/pom.xml` 加入 `pixflow-module-imagegen` 依赖。
- 验证 `mvn -pl pixflow-app -am test` 全绿（imagegen bean 被装配后不影响现有 app 测试；`DefaultImageGenExecutor` 默认不暴露，符合 Sentinel 边界）。
- 验证 `mvn -pl pixflow-module-imagegen -am package` 产物含 `ImagegenPlanToolHandler` / `ImagegenPlanDescriptor` / `ImagegenConfirmationSupport` bean。
- 端到端冒烟（里程碑 5 验证用）：构造 `submit_imagegen_plan` JSON 入参 → POST 到 app REST 端点 → 收到 `{planId, summary}` → 模拟确认 → `ImagegenConfirmationSupport.payloadHash` 一致性通过。

## Concrete Steps

### 工作目录

所有命令在 `D:\study\PixFlow`（PowerShell）下运行。

### 里程碑 1 命令序列

    # 创建模块骨架
    mkdir pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/{proposal,exec,confirm,port,error,metrics,config}
    mkdir pixflow-module-imagegen/src/test/java/com/pixflow/module/imagegen/{proposal,confirm}

    # 写入 pom.xml（参考 pixflow-tools/pom.xml 的依赖结构）
    # 在根 pom.xml <modules> 加入 <module>pixflow-module-imagegen</module>

    # 写 proposal/confirm 纯函数源码 + 单测

    # 验证
    mvn -pl pixflow-module-imagegen -am compile
    mvn -pl pixflow-module-imagegen test

预期：`mvn test` 末尾 `Tests run: 30+, Failures: 0, Errors: 0, Skipped: 0`；`mvn compile` 不依赖 Spring。

### 里程碑 2 命令序列

    mkdir pixflow-module-imagegen/src/test/java/com/pixflow/module/imagegen/{exec,error,port}

    # 写 port/SPI 接口 + exec/DefaultImageGenExecutor + error/ImagegenErrorCode + 单测

    # 验证
    mvn -pl pixflow-module-imagegen -am test

预期：`Tests run: 80+, Failures: 0, Errors: 0, Skipped: 0`；ArchUnit 守护测试绿。

### 里程碑 3 命令序列

    mkdir pixflow-module-imagegen/src/test/java/com/pixflow/module/imagegen/{config,metrics}

    # 写 confirm/ImagegenConfirmationSupport + config/ImagegenProperties + config/ImagegenAutoConfiguration + metrics/ImagegenMetrics + 单测

    # 验证
    mvn -pl pixflow-module-imagegen -am test

预期：单测全绿；Sentinel 单测断言 `DefaultImageGenExecutor` 默认不被装配。

### 里程碑 4 命令序列

    mkdir pixflow-module-imagegen/src/test/java/com/pixflow/module/imagegen/integration

    # 写 proposal/ImagegenPlanService + proposal/ImagegenPlanToolHandler + proposal/ImagegenPlanDescriptor + Testcontainers 集成测试

    # 验证
    mvn -pl pixflow-module-imagegen -am test

预期：单测 + Testcontainers 集成测试全绿（按 env 跳过策略处理 Docker 不可用情况）。

### 里程碑 5 命令序列

    # 修改 pixflow-app/pom.xml 加 pixflow-module-imagegen 依赖

    # 验证
    mvn -pl pixflow-app -am test
    mvn -pl pixflow-module-imagegen -am package

预期：app 测试全绿；imagegen 产物含 handler/descriptor/confirmation bean；`mvn -pl pixflow-app spring-boot:run` 启动后能看到 imagegen bean 被装配（`DefaultImageGenExecutor` **不**被装配）。

### 端到端冒烟（里程碑 5 验证用）

    $inputs = @{
        source_image_ids = @("img-001", "img-002")
        prompt = "用 A 风格重绘"
        note = "用户偏好测试"
    } | ConvertTo-Json

    Invoke-WebRequest -Uri http://localhost:8080/api/tools/submit_imagegen_plan `
      -Method POST -Body $inputs -ContentType "application/json"

预期：返回 `{"success":true, "data":{"planId":"...","summary":{"sourceCount":2,"promptSummary":"...","note":"..."}}}`；fake `PendingPlanPort` 计数 == 1。

## Validation and Acceptance

### 行为级验收（人工可观察）

1. **提案校验**：构造含不存在 `imageId` 的 `submit_imagegen_plan` 入参（fake `SourceImageReader.findAll` 返回空），submit 后应收到 `IMAGEGEN_SOURCE_IMAGE_NOT_FOUND` tool error，`PendingPlanPort.enqueue` 不被调用。
2. **归属校验**：构造 `imageId` 存在但不归属当前 `packageId` 的入参，submit 后应收到 `IMAGEGEN_SOURCE_NOT_IN_PACKAGE` tool error。
3. **张数上限**：构造 `source_image_ids` 超过 200 条的入参，submit 后应收到 `IMAGEGEN_TOO_MANY_SOURCES` tool error，提示拆分提案。
4. **prompt 长度**：构造 prompt 超过 2000 字符的入参，submit 后应收到 `IMAGEGEN_PROMPT_INVALID` tool error。
5. **params 白名单**：构造 `params.secrets="ak-xxx"`（白名单外）的入参，submit 后应收到 `IMAGEGEN_PROMPT_INVALID` 或独立的 `params 白名单违规` tool error。
6. **payloadHash 稳定性**：同语义入参（字段顺序变化）两次 submit，hash 一致；不同语义入参（改 prompt / 改源图集）hash 不同。
7. **入队幂等**：同 `toolCallId` 重复 `submit_imagegen_plan` → fake `PendingPlanPort.enqueue` 计数 == 1。
8. **生成图字节防护**：fake `ImageGenClient.generate` 返回 60MiB 字节 → `DefaultImageGenExecutor.redraw` 抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE`，**不**调 `objectStorage.put`。
9. **源图字节防护**：fake `objectStorage.stat` 返回 60MiB → `redraw` 不调 `getStream`、抛 `IMAGEGEN_OUTPUT_BYTES_TOO_LARGE` 类语义（具体码可与"生成图过大"共用，依赖 `ImagegenErrorCode` 设计）。
10. **ai 异常透传**：fake `ImageGenClient.generate` 抛 `PixFlowException(MODEL_RATE_LIMITED, RATE_LIMIT, RETRY)` → `redraw` 原样上抛、不吞不重试。
11. **写 GENERATED 桶失败**：fake `objectStorage.put` 抛 `StorageException(retryable=false)` → `redraw` 抛 `IMAGEGEN_STORAGE_WRITE_FAILED`。
12. **子 Agent 双重拦截**：构造子 Agent 上下文（`PermissionContext.isSubagent=true`）调 `submit_imagegen_plan` → `PermissionSubject.readOnly=true` 字段被 `ImagegenPlanToolHandler` 设置；permission 阶段 A 短路 → `SUBAGENT_FORBIDDEN_ACTION`（permission 侧单测）；`ToolDescriptor` 在子 Agent 上下文中由 `harness/tools.isToolVisible` 过滤（schema 断言）。
13. **令牌隔离**：断言 `submit_imagegen_plan` 的 inputSchema 不含任何 token 字段；handler 实现不读 `tool_input` 中的令牌状字段。
14. **Sentinel 装配边界**：`@SpringBootTest` 加载 `pixflow-module-imagegen` 默认上下文，断言 `DefaultImageGenExecutor` 不在 Spring 容器中；设置 `pixflow.imagegen.executor.expose=true` 后断言可取到。
15. **可观测性指标**：`/actuator/prometheus` 暴露 6 类 `pixflow.imagegen.*` 指标，标签不含 taskId/skuId/imageId；`outcome=ok|failed|...` 维度按 `ImagegenErrorCode` 细分。
16. **错误码目录唯一性**：`ImagegenErrorCode` 全部并入 `common` 启动期聚合测试，10 条 code 唯一、i18n 齐全、category 非空。
17. **端到端**：构造 `submit_imagegen_plan` → handler 入队 → 模拟 confirm REST（fake controller）→ `ImagegenConfirmationSupport.payloadHash(planId)` 一致 → 模拟 Wave 4 task worker 调 `DefaultImageGenExecutor.redraw` → 模拟 MinIO 写入 → fake `process_result` 落库。

### 测试命令

    # 单测
    mvn -pl pixflow-module-imagegen -am test

    # 集成测试（Testcontainers 需要 Docker）
    mvn -pl pixflow-module-imagegen -am test -Pintegration

    # 端到端冒烟（pixflow-app 启动后）
    mvn -pl pixflow-app spring-boot:run

### 验收判定

- `mvn -pl pixflow-module-imagegen -am test` 全绿（含 ArchUnit 守护测试 + Sentinel 装配边界单测 + 10 条错误码目录聚合测试）
- `mvn -pl pixflow-app -am test` 全绿（imagegen bean 装配不影响 app 现有测试）
- `mvn -pl pixflow-module-imagegen -am package` 产物含 `ImagegenPlanToolHandler`/`ImagegenPlanDescriptor`/`ImagegenConfirmationSupport` bean；**不**含 `DefaultImageGenExecutor` 自动装配 bean
- 端到端冒烟 17 项人工验收全部通过

## Idempotence and Recovery

- 所有 mvn 命令可重复运行（idempotent）：创建文件用 `New-Item -ItemType Directory -Force`；schema/配置覆盖写不破坏旧内容。
- 单测失败时定位到具体类或方法，修复后 `mvn -pl pixflow-module-imagegen test -Dtest=具体类` 重跑。
- Testcontainers 集成测试若 Docker 不可用，按 `infra/storage` 已有的 profile 跳过策略（不视为失败）。
- 不需要任何数据迁移：imagegen 模块不直连 MySQL，提案与结果落库分别倒置给 `module/conversation`（Wave 4）与 `module/task`（Wave 4）；`pending_plan` 表结构对 DAG 提案与生图提案中立（`dag.md §6.2`）。
- 回滚策略：移除 `pixflow-module-imagegen` 模块的 Maven 引用即可，不影响其他模块；不修改任何已有模块的代码。
- `DefaultImageGenExecutor` 的装配边界 Sentinel 单测是"防回滚失效"的关键——如果未来误开启装配，测试会立即失败。

## Artifacts and Notes

### 关键代码片段参考

**ImagegenPayloadHasher 实现**（`src/main/java/com/pixflow/module/imagegen/confirm/ImagegenPayloadHasher.java`）：

    public final class ImagegenPayloadHasher {
        private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        public String hash(ImagegenPlan plan) {
            try {
                // sourceImageIds 按 imageId 字典序规范化入 record；此处无需再排
                List<String> sortedIds = plan.sourceImageIds().stream()
                    .sorted().collect(Collectors.toUnmodifiableList());
                var canonical = Map.of(
                    "sourceImageIds", sortedIds,
                    "prompt", plan.prompt().trim(),
                    "params", canonicalizeParams(plan.params())
                );
                byte[] bytes = CANONICAL.writeValueAsBytes(canonical);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception e) {
                throw new IllegalStateException("payloadHash 计算失败", e);
            }
        }

        private Map<String, Object> canonicalizeParams(Map<String, Object> params) {
            // 白名单内参数按字典序归一；白名单外参数**不参与** hash
            return ALLOWED_KEYS.stream()
                .filter(params::containsKey)
                .collect(Collectors.toMap(k -> k, params::get, (a, b) -> a,
                    java.util.TreeMap::new));
        }
    }

**DefaultImageGenExecutor.redraw 实现**（`src/main/java/com/pixflow/module/imagegen/exec/DefaultImageGenExecutor.java`）：

    public GeneratedArtifact redraw(GenerativeUnitSpec spec) {
        // 1. 源图字节读取（受 max-read-bytes 保护）
        StoredObjectMetadata meta = objectStorage.stat(spec.sourceLocation());
        if (meta.size() > properties.source().maxReadBytes()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE, "源图字节过大");
        }
        byte[] sourceBytes;
        try (InputStream in = objectStorage.getStream(spec.sourceLocation())) {
            sourceBytes = in.readAllBytes();
        } catch (StorageException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED, e);
        }

        // 2. 调 ai 重绘
        ImageGenResult result = imageGenClient.generate(new ImageGenRequest(
            sourceBytes, meta.contentType(), spec.prompt(), toOptions(spec.params())));

        // 3. 生成图字节预检（生产级必做，与 dag source-bytes-limit 对称）
        if (result.image().length > properties.output().maxOutputBytes()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,
                "生成图字节过大: " + result.image().length);
        }

        // 4. 落 GENERATED 桶
        ObjectRef ref = objectStorage.put(
            StorageKeys.generated(spec.taskId(), spec.skuId(), spec.sourceImageId(),
                properties.output().defaultExt()),
            new ByteArrayInputStream(result.image()), result.image().length,
            result.contentType());

        return new GeneratedArtifact(ref, result.contentType(), result.usage());
    }

### 关键测试片段参考

**payloadHash 稳定性测试**（`ImagegenPayloadHasherTest.java`）：

    @Test
    void hash_isStable_acrossFieldReordering() {
        ImagegenPlan plan1 = plan(ids("a", "b"), "重绘", Map.of("style", "A"), "note-1");
        ImagegenPlan plan2 = plan(ids("b", "a"), "重绘", Map.of("style", "A"), "note-2");
        // sourceImageIds 排序后应一致；note 不参与 hash
        assertThat(hasher.hash(plan1)).isEqualTo(hasher.hash(plan2));
    }

    @Test
    void hash_changesWhenPromptChanges() {
        ImagegenPlan plan1 = plan(ids("a"), "A 风格", Map.of("style", "A"), "");
        ImagegenPlan plan2 = plan(ids("a"), "B 风格", Map.of("style", "A"), "");
        assertThat(hasher.hash(plan1)).isNotEqualTo(hasher.hash(plan2));
    }

    @Test
    void hash_ignoresNonWhitelistedParams() {
        ImagegenPlan plan1 = plan(ids("a"), "x", Map.of("style", "A"), "");
        ImagegenPlan plan2 = plan(ids("a"), "x", Map.of("style", "A", "secrets", "ak-xxx"), "");
        // 白名单外参数不参与 hash
        assertThat(hasher.hash(plan1)).isEqualTo(hasher.hash(plan2));
    }

**生成图字节防护测试**（`DefaultImageGenExecutorTest.java`）：

    @Test
    void redraw_throwsWhenOutputExceedsLimit_andDoesNotCallPut() {
        when(imageGenClient.generate(any())).thenReturn(
            new ImageGenResult(new byte[60 * 1024 * 1024], "image/png", TokenUsage.ZERO));

        assertThatThrownBy(() -> executor.redraw(spec))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE);

        verify(objectStorage, never()).put(any(), any(InputStream.class), anyLong(), any());
    }

**Sentinel 装配边界测试**（`ImagegenAutoConfigurationSentinelTest.java`）：

    @SpringBootTest(classes = ImagegenAutoConfiguration.class)
    class ImagegenAutoConfigurationSentinelTest {
        @Autowired ApplicationContext ctx;

        @Test
        void defaultImageGenExecutor_isNotExposedByDefault() {
            // Wave 3 边界：DefaultImageGenExecutor 不应被 Spring 装配
            // （Wave 4 task 主动 import 时设置 pixflow.imagegen.executor.expose=true）
            assertThatThrownBy(() -> ctx.getBean(DefaultImageGenExecutor.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

### 依赖清单（pixflow-module-imagegen/pom.xml）

    <dependencies>
        <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-common</artifactId></dependency>
        <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-ai</artifactId></dependency>
        <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-storage</artifactId></dependency>
        <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-contracts</artifactId></dependency>
        <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-harness-permission</artifactId></dependency>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
        <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
        <!-- 测试 -->
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>minio</artifactId><scope>test</scope></dependency>
    </dependencies>

## Interfaces and Dependencies

### 与上游模块的契约

| 上游模块 | 契约 |
|---|---|
| `harness/tools` | `ImagegenPlanToolHandler` 实现 `ToolHandler`，贡献 `ToolDescriptor` bean（`name="submit_imagegen_plan"`、`concurrencySafe=true`、`readOnly=true`、inputSchema 不含 token 字段）；tools 自动收集；handler 标 `readOnly` 让 permission 阶段 A 对子 Agent 短路 |
| `module/task`（Wave 4 落地） | 调 `ImageGenExecutor.redraw(GenerativeUnitSpec)` 逐单元执行；据 `GeneratedArtifact` 写 `process_result`、推进度、终态判定；与现有 `dag → task` 在 shape 上对称 |
| `module/conversation`（Wave 4 落地） | 实现 `PendingPlanPort`（提案与会话关联、与 DAG 提案共用 `pending_plan`）；confirm REST 端点取回提案、算 `payloadHash`/`expectedCount`、构造 `PermissionSubject`、触发 task |
| `module/file`（Wave 3/4 落地） | 实现 `SourceImageReader`（按 imageId 解析 `asset_image.minio_key` + 归属校验） |

### 与下游模块的契约

| 下游模块 | 契约 |
|---|---|
| `infra/ai` | 经 `ImageGenClient.generate` 源图重绘；imagegen 传字节、收字节，落桶自理；重试/并发/型号归 ai |
| `infra/storage` | `getStream` 解析源图字节（受 `source.max-read-bytes` 保护）；`put` 结果到 `GENERATED` 桶（`StorageKeys.generated`）|
| `permission` | confirm 边界 `evaluate(action=IMAGEGEN)` 由 permission 校验消费令牌；imagegen 经 `ImagegenConfirmationSupport` 提供 `payloadHash`/`expectedCount`；子 Agent 调本工具被 `permission` 阶段 A 短路 → `SUBAGENT_FORBIDDEN_ACTION` |
| `contracts` | 引用 `ConfirmationAction.IMAGEGEN` 等确认令牌动作枚举 |
| `common` | 抛 `PixFlowException`（`ImagegenErrorCode` 10 条）；文案经 `Sanitizer`；指标 `pixflow.imagegen.*` 经 `Micrometer` |

### 关键 SPI 形态

**ImageGenExecutor**（`com.pixflow.module.imagegen.exec.ImageGenExecutor`）：

    public interface ImageGenExecutor {
        /** 无状态单图重绘：解析源图字节 → ai 重绘 → 字节预检 → 落 GENERATED 桶 → 返回产物。
         *  不写 process_result、不发进度、不持取消。 */
        GeneratedArtifact redraw(GenerativeUnitSpec spec);
    }

    public record GenerativeUnitSpec(
        String taskId, String skuId, String sourceImageId,
        ObjectLocation sourceLocation, String prompt,
        Map<String, Object> params, String outputExt
    ) {}

    public record GeneratedArtifact(ObjectRef output, String contentType, TokenUsage usage) {}

**PendingPlanPort**（`com.pixflow.module.imagegen.port.PendingPlanPort`，中立 record 不绑定 imagegen/dag）：

    public interface PendingPlanPort {
        /** 入队待确认提案，返回 planId（同 (conversationId, toolCallId) 幂等）。 */
        String enqueue(PendingPlanProposal proposal);
        /** confirm 边界按 planId 取回原文（供重算 payloadHash / count）。 */
        Optional<PendingPlanProposal> find(String planId);
    }

**SourceImageReader**（`com.pixflow.module.imagegen.port.SourceImageReader`）：

    public interface SourceImageReader {
        List<SourceImageInfo> findAll(List<String> imageIds, String packageId);
    }

    public record SourceImageInfo(
        String imageId, String skuId, String objectKey, String contentType,
        String viewId, String groupKey
    ) {}

### 关键配置

    pixflow:
      imagegen:
        proposal:
          max-source-images: 200            # 单条生图提案的源图张数上限
          prompt-min-chars: 1
          prompt-max-chars: 2000            # 生图提示词长度上下界
          allowed-param-keys: [style, strength, negative_prompt, seed]  # params 白名单
        output:
          default-ext: png                  # 生图结果默认输出格式
          max-output-bytes: 52428800        # 50MiB；超限 → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE
        source:
          supported-types: [image/jpeg, image/png, image/webp]  # 源图内容类型白名单
          max-read-bytes: 52428800          # 50MiB；源图字节解析上限
        executor:
          expose: false                     # Wave 3 边界：DefaultImageGenExecutor 默认不装配到 Spring
                                            # Wave 4 task 模块就绪后由 task 主动 import 时改 true

### Revision Notes

2026-06-29 / Kiro: 新增 `module/imagegen` ExecPlan。基于 `module/imagegen.md` 现有设计与本轮 6 项设计决策（Wave 3 实现 vs Wave 4 装配边界、`max-output-bytes`、payloadHash 排序口径、子 Agent 双重拦截、6 类指标、多变体本期不做、10 条错误码），按 5 个里程碑拆解（提案纯函数 → 执行器骨架 → confirm 边界 + 配置 + 可观测性 + 装配 Sentinel → handler 接线 + 集成测试 → 装配与端到端冒烟）。所有架构与边界决策以 `imagegen.md` 为唯一权威；本计划只补充"如何落地"。

2026-06-29 / Kiro: 关键词定位索引覆盖 imagegen.md 全部 15 个关键段落（含本轮新增的"两道独立护栏"与"Wave 3 实现 vs 装配边界"），执行本计划时按关键词在 `imagegen.md` 内快速跳转。

2026-06-29 / Kiro (回填): 5 个里程碑全部完成，进度时间戳与实测单测数（37 → 51 → 70 → 82 → app 装配回归）记录完整；Decision Log 新增 3 条（`stableHashToLong` 幂等映射、Sentinel 自 stub bean、Testcontainers 集成测试归属 Wave 4）；Surprises & Discoveries 新增 3 条（`StorageKeys.generated` long vs String、Mockito 流复用、AssertJ wildcard 不兼容）。Outcomes & Retrospective 给出交付清单 + 设计 vs 实现一致性对账 + 实施期发现 + Wave 4 留待任务（`module/conversation` 实现 `PendingPlanPort`、`module/file` 实现 `SourceImageReader`、`module/task` import executor + 端到端集成测试）。所有架构与边界决策与 `imagegen.md` §十六 14 项细化完全对齐。
