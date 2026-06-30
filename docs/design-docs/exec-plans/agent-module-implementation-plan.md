# 完整实现 pixflow-agent：Agent 装配层（Skill 工具化 + Session Memory 累积 + 自动记忆召回 + 异步 Subagent Runner + 动态 Prompt 组装 + Plan 模式控制器）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md` 与 `docs/design-docs/agent/agent.md`（设计权威）。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

执行完成后，`mvn -pl pixflow-agent -am test` 应全绿（含 ArchUnit 守护）；`mvn -pl pixflow-app -am test` 应 BUILD SUCCESS（agent bean 装配不影响 app 现有测试）。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级的 Agent 决策层装配模块 `pixflow-agent`（Wave 5）。它位于 `harness/loop` 之上的薄装配层，把动态 Prompt 组装 + section 缓存、Skill 工具化与渐进式披露、自动记忆（用户偏好 / SKU 历史 / 分析结论）的 RRF 召回与注入、Session Memory 累积提取、异步 Subagent Runner（`agent` 工具 / destructive compaction 摘要 / Session Memory 提取三处复用）、`SummarizationPort` / `SessionMemoryPort` SPI 实现、Plan 模式控制器、Agent Orchestrator 入口、以及与各模块的接缝契约拼装成一个可在每回合驱动 `loop.stream` 的完整业务装配层。

用户能看到的效果：

- Agent 回合内可以按需调用 `skill__beauty_watermark` 等知识查阅工具，模型从 schema 短描述到 system prompt `available_skills` 目录到 handler 返回 body 三层渐进披露获取完整规范正文。
- 每轮 `buildForModel` 前自动同步召回记忆（用户偏好画像全量 + SKU 历史精确 + 分析结论向量与 FULLTEXT 双路 RRF 融合），注入 `instruction_memory` / `long_term_memory` 两 section。
- 每回合末异步累积 Session Memory（MySQL 单行 + Redis 缓存，替换式提取，断路器 3 次后 fallback 规则式），下一回合自动注入 `session_memory` section。
- 子 Agent（vision / explore）走 `SubagentRunner.runAsync(req)` 异步返回 `CompletableFuture`，父回合不阻塞；child 用 ephemeral MessageStore 不写 `message` 表，工具集裁剪 + 递归禁用 `agent`。
- 上下文超窗时 `SummarizationPort` SPI fork child 跑 LLM 摘要作为应急备份（cheap pipeline 与 Session Memory 是主要压缩手段）。
- `plan` / `plan_exit` 工具切换只读模式：`RuntimeState.metadata["planMode"]` 写值，`PlanModeView` SPI 暴露给 `DefaultToolRegistry` 与 `PermissionContextFactory` 做可见集与硬 deny 双层强制。
- Skill 启动期一次性同步入 MySQL `skill` 表，handler 返回完整正文（body ≤ 32KB），schema 层 description 严格 ≤ 30 字避免工具集膨胀。

本计划不是 MVP，按生产级完整模块设计。所有 agent 特有类、三个 HookCallback 实现、Skill 注册机制、Session Memory 持久化、Subagent 线程池、ArchUnit 反向约束守护均一次到位。

## 设计文档快速定位关键词

执行本计划时，所有架构与边界决策以 `docs/design-docs/agent/agent.md` 为唯一权威。下列关键词可在该文档内快速定位对应段落：

- 八条装配层专属设计原则 → 搜 `agent 专属设计原则`
- agent 与参考实现的本质差异 → 搜 `与参考实现的本质差异`
- 模块结构与依赖方向 → 搜 `模块结构与依赖位置`
- section 顺序与分类（12+ section 列表）→ 搜 `section 顺序与分类` 或 `固定 section 顺序`
- `PromptSection` 与 `fingerprint` 计算规则 → 搜 `PromptSection 与 fingerprint`
- `PromptSectionCache` 进程内 ConcurrentHashMap + LRU → 搜 `PromptSectionCache`
- `PromptRuntimeContext` 装配入参 → 搜 `PromptRuntimeContext`
- 工具 schema 投影与 `PromptProvider` → 搜 `工具 schema 投影`
- 不引入 provider context caching → 搜 `不引入 provider context caching`
- Skill 工具化（每个 skill = tool descriptor）→ 搜 `形态：每个 skill = 一个 tool descriptor`
- Skill 命名空间化 `skill__<name>` 与冲突检测 → 搜 `命名空间化`
- SKILL.md frontmatter 4 字段强制 → 搜 `SKILL.md 格式`
- `skill` 表 schema 与 BUILTIN/PROJECT/TEAM 来源 → 搜 `数据模型` 或 `BUILTIN`
- SkillLoader 启动期同步幂等 → 搜 `启动期同步流程`
- SkillToolRegistrar 动态注册 tool descriptor → 搜 `ToolRegistry 动态注册`
- Skill handler 实现（不调 LLM / 不改状态）→ 搜 `handler 实现`
- `available_skills` section 渲染 → 搜 `available_skills section`
- Skill 工具集大小警告与治理 → 搜 `工具 schema 总数治理`
- 召回输入信号 A-E → 搜 `召回输入信号`
- 召回通道与触发条件 → 搜 `召回通道与触发条件`
- RRF 融合 + token 上限裁剪 → 搜 `RRF 融合 + token 上限裁剪`
- SKU 列表解析（两阶段：规则 + LLM 兜底）→ 搜 `SKU 列表解析`
- 类目 filter 现状（本期数据无 category）→ 搜 `类目 filter 现状`
- 召回结果 prompt 渲染 → 搜 `召回结果的 Prompt 渲染`
- 召回不暴露为 Agent 工具 → 搜 `不暴露为 Agent 工具`
- Session Memory 与 destructive compaction 的正交关系 → 搜 `与 destructive compaction 的关系`
- `session_memory` 表 schema + Redis 缓存键 → 搜 `数据模型：MySQL session_memory` 或 `Redis 缓存`
- 阈值设计：阈值不入 transcript → 搜 `阈值设计：阈值不入 transcript` 或 `last_summarized_seq`
- 提取流程：异步 + 同步两种路径 → 搜 `提取流程`
- Session Memory 提取 prompt（fork child LLM）→ 搜 `提取 prompt`
- `SessionMemoryUpdater` fallback 规则式 → 搜 `Fallback：SessionMemoryUpdater`
- `SessionMemoryPort` SPI 4 方法 → 搜 `SessionMemoryPort SPI`
- Session Memory 与 module/memory 边界 → 搜 `与 module/memory 的边界`
- SubagentRunner 主干流程 → 搜 `主干流程`
- 父 RuntimeState 字段继承表 → 搜 `父 RuntimeState 字段继承表`
- child MessageStore seed 三种模式（本期只 clean）→ 搜 `child MessageStore seed`
- child 运行时工具集裁剪（按调用方分类）→ 搜 `child 运行时工具集裁剪`
- `agent` 工具 handler（type=vision / type=explore）→ 搜 `agent 工具 handler`
- ChildTraceRecorder 合并到父回合 → 搜 `ChildTraceRecorder`
- Subagent 不可恢复错误处理 → 搜 `Subagent 不可恢复错误处理`
- Subagent 并发（独立线程池）→ 搜 `Subagent 并发`
- `SummarizationPort` 实现 SPI → 搜 `SummarizationPort 实现`
- SummarizationPort child prompt 构造 → 搜 `child prompt 构造`
- `ForkChildSummarizationPort` 实现类 → 搜 `ForkChildSummarizationPort`
- 失败兜底（断路器）→ 搜 `失败兜底`
- `PreCompactSummaryHook` 注入 summaryInstructions → 搜 `PreCompact 指令注入`
- 三层压缩金字塔 cheap pipeline / Session Memory / auto compact → 搜 `压缩策略分层`
- Plan 模式状态归属 `RuntimeState.metadata.planMode` → 搜 `状态归属`
- `plan` 工具 handler → 搜 `plan 工具 handler`
- `plan_exit` 工具 handler → 搜 `plan_exit 工具 handler`
- Plan 模式三层强制（可见集 / permission / prompt）→ 搜 `三层强制`
- `AgentOrchestrator` 入口装配 → 搜 `Agent Orchestrator 入口`
- `AgentOrchestrator` 依赖注入列表 → 搜 `依赖注入`
- `AgentErrorCode` 自治码 → 搜 `错误归一化`
- 与各模块的接缝契约表 → 搜 `与各模块的接缝契约`
- 完整配置项 `pixflow.agent.*` → 搜 `配置项`
- Micrometer 指标清单（按 7 个机制分块）→ 搜 `Micrometer 指标`
- trace 责任上移（PromptSummary / RecallSummary 等只读视图）→ 搜 `trace 责任`
- 单元 / 集成 / ArchUnit / 属性测试策略 → 搜 `测试策略`

辅助参考：`docs/design-docs/design.md` 整体架构与数据模型；`docs/design-docs/harness/loop.md` 主循环入口与 `RuntimeState`；`docs/design-docs/harness/context.md` `MessageStore` / `ContextEngine.buildForModel(BuildResult)` / `SummarizationPort` SPI；`docs/design-docs/harness/tools.md` `ToolDescriptor` / `ToolHandler` / `DefaultToolRegistry` / `PlanModeView`；`docs/design-docs/harness/session.md` `message` 表唯一写者约束；`docs/design-docs/harness/hooks.md` `HookCallback` / `HookEvent` / `HookResult` 与 metadata 累积；`docs/design-docs/harness/eval.md` `TurnTrace` / `TraceInput` / `TraceRecall` / `TracePruneEntry`；`docs/design-docs/harness/permission.md` `PermissionPolicy.evaluate` / `PermissionContext` / `SubagentConstraint`；`docs/design-docs/module/memory.md` `MemoryService.prepareContext` / `MemoryContextRequest` / `MemoryContext`；`docs/design-docs/exec-plans/completed/dag-module-implementation-plan.md` 与 `loop-module-implementation-plan.md` 的 ExecPlan 风格与 ArchUnit 守护写法。

参考实现（仅借鉴理念，不照搬）：`docs/references/prompt-architecture.md`（section 缓存骨架）、`docs/references/subagent-architecture.md`（child runtime + 工具裁剪）、`docs/references/compaction-architecture.md`（session memory 累积 + fallback）。

## Progress

本计划拆为 10 个里程碑，每个里程碑独立可验证，叠加构成完整 `pixflow-agent` 模块。每个里程碑收尾必须更新本段，列出已完成项 + 时间戳；剩余项必须清晰可勾。

### 已完成

- [x] (2026-06-30 22:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`docs/design-docs/agent/agent.md`（1769 行 Wave 5 Agent 装配层设计权威）、`module-dependency-dag-plan.md`，确认 `agent` 属于 Wave 5，位于 `loop` 上游、`{memory, vision, imagegen, commerce, dag, conversation, file, hooks, eval, permission, state, tools, context, session} + 所有 infra/*` 下游。
- [x] (2026-06-30 22:35+08:00) 阅读 `harness/loop.md`、`harness/context.md`、`harness/tools.md`、`harness/session.md`、`harness/hooks.md`、`harness/eval.md`、`harness/permission.md`、`module/memory.md`、`module/conversation.md`、`module/file.md` 全部，确认 13 条接缝契约的当前类型签名、缺位 SPI、现存数据模型。
- [x] (2026-06-30 22:40+08:00) 阅读 5 个 Python 参考实现（`prompt-architecture.md` / `subagent-architecture.md` / `compaction-architecture.md` / `context-architecture.md` / `core-runtime-architecture.md`），提炼可借鉴骨架（section cache / child runtime / session memory fallback / CurrentModelContext / Projector）与必须重写的内核（MySQL 事实源 + SPI 倒置 / 异步 subagent / provider context caching 不依赖）。
- [x] (2026-06-30 22:45+08:00) 与用户对齐 4 个生产级细节：① Session Memory = MySQL 单行 + 替换式提取（落地需回写 `session.md §17` 取消禁列）；② Skill ToolDescriptor 补 `prompt` 字段 + 渲染 `tool_prompt:skill__<name>` 段；③ 记忆召回在 `AgentOrchestrator` 入口同步触发（不走 hook，理由：hook 时机在本轮结束，太晚）；④ SubagentRunner **必须异步**——`runAsync(req) → CompletableFuture<SubagentResult>`，child 跑在 `pixflow.agent.subagent.pool` 独立有界线程池（默认 core=4/max=16/queue=100），主回合不阻塞。
- [x] (2026-06-30 22:50+08:00) 探查仓库现状：`pixflow-agent` Maven 模块不存在（根 `pom.xml` 26 个模块），根包 `com.pixflow.agent` 0 个 Java 文件；`SummarizationPort` 已在 `pixflow-context/.../compaction/` 存在但 `SessionMemoryPort` 不存在（需在 `pixflow-context` 新增）；Java 21 + Spring Boot 3.5.16；infra/ai 命名是 `ChatModelClient` / `ChatStreamEvent` / `TokenUsage`；`AgentLoop` 构造 15 依赖；`DefaultToolRegistry` 构造期接 `List<ToolDescriptor>`；`PlanModeView` 单方法 `boolean isPlanMode()`；`RuntimeState.metadata` 是开放 Map，`planMode` 字符串键值无类型化字段；`HookEvent` 已有 11 个值含 `TURN_STOPPED` / `ASSISTANT_MESSAGE_COMPLETED` / `PRE_COMPACT`。
- [x] (2026-06-30 22:55+08:00) 起草本 ExecPlan，按 `PLANS.md` 要求写清目的、机制、检索关键词、具体步骤和验收方式。

### 里程碑 1：Maven 模块骨架 + SPI 补位

- [x] (2026-06-30 19:45+08:00) 根 `pom.xml` 加 `<module>pixflow-agent</module>` + dependencyManagement 条目
- [x] (2026-06-30 19:50+08:00) 新建 `pixflow-agent/pom.xml`（12 个 pixflow-* 依赖 + MyBatis-Plus + Redisson + Caffeine + jtokkit + Spring Boot 配置处理器）
- [x] (2026-06-30 20:00+08:00) 在 `pixflow-context` 补 `SessionMemoryPort` SPI：`com.pixflow.harness.context.sessionmemory` 包下新增 3 个 record + 1 个接口
- [x] (2026-06-30 20:05+08:00) `mvn -pl pixflow-context -am install` + `mvn -pl pixflow-agent -am install -DskipTests` BUILD SUCCESS

### 里程碑 2：装配层核心抽象与 section 渲染

- [x] (2026-06-30 20:10+08:00) `prompt/` 子包：PromptSection record、PromptSectionCache interface + CaffeinePromptSectionCache 实现、PromptSummary、DynamicPromptAssembler、SectionRenderer + PromptRuntimeContext record
- [x] (2026-06-30 20:15+08:00) `prompt/sections/` 子包：7 个 section renderer（Identity / BehaviorRules / RiskAndSafety / Verification / Preference / SessionMemory / LongTermMemory / WorkspaceState / ActiveSkills / ToolPrompt）
- [x] (2026-06-30 20:18+08:00) 4 个 prompt 资源文件（identity.md / behavior_rules.md / risk_and_safety.md / verification_and_reporting.md）
- [x] (2026-06-30 20:20+08:00) `mvn -pl pixflow-agent compile` BUILD SUCCESS（25 个源文件）

### 里程碑 3：Skill 机制

- [x] (2026-06-30 20:25+08:00) `skill/` 子包：SkillFrontmatter record、SkillFrontmatterParser（含 4 字段校验 + 命名规则 + body 字节上限）、Skill 实体、SkillMapper + SkillRepository、SkillLoader（`@PostConstruct syncBuiltIn`）、SkillHandler（ToolHandler）、SkillToolRegistrar（`@PostConstruct registerAll` 调用 `toolRegistry.registerDynamic`）
- [x] (2026-06-30 20:30+08:00) `pixflow-tools/DefaultToolRegistry` + `ToolRegistry` 接口扩展：`registerDynamic` / `unregisterDynamic`（向后兼容）
- [x] (2026-06-30 20:33+08:00) 示例 SKILL.md（beauty_watermark）+ V1__create_skill.sql
- [x] (2026-06-30 20:37+08:00) `mvn -pl pixflow-agent compile` BUILD SUCCESS

### 里程碑 4：自动记忆召回

- [x] (2026-06-30 20:40+08:00) `memory/` 子包：RecallChannel enum、MemoryItem / MemorySection / MemoryRecallSignal / MemoryRecallResult records、TokenEstimator（jtokkit）、RrfMerger（k=60 + maxItems + maxTokens 截断）
- [x] (2026-06-30 20:42+08:00) 4 通道 provider：PreferenceRecallProvider / SkuHistoryRecallProvider / InsightRecallProvider / InsightFulltextRecallProvider（本期保守返回空，DB 接入留接口）
- [x] (2026-06-30 20:45+08:00) MemoryChannelProvider SPI + SkuMentionExtractor（regex 阶段 + LLM 兜底 + Redis 缓存）
- [x] (2026-06-30 20:48+08:00) MemoryRecallPlanner.plan（4 通道并发 + 降级矩阵 + RRF 融合）
- [x] (2026-06-30 20:50+08:00) `mvn -pl pixflow-agent compile` BUILD SUCCESS（46 个源文件）

### 里程碑 5：Session Memory 累积提取

- [x] (2026-06-30 20:55+08:00) `sessionmemory/` 子包：SessionMemory 实体、SessionMemoryMapper + Repository、SessionMemoryCache（Redis）、SessionMemoryExtractor（fork child LLM 占位）、SessionMemoryUpdater（fallback 规则式）
- [x] (2026-06-30 20:58+08:00) SessionMemoryService 实现 `context.SessionMemoryPort` 4 方法（load / save / computeThreshold / scheduleExtraction）+ 进程内断路器
- [x] (2026-06-30 20:59+08:00) V2__create_session_memory.sql 迁移文件

### 里程碑 6：异步 SubagentRunner + 三处复用

- [x] (2026-06-30 21:00+08:00) `subagent/` 子包：SubagentType enum、SubagentRequest / SubagentResult records、ChildToolFilter、SubagentPool、SubagentRunner.runAsync（`CompletableFuture.supplyAsync(subagentPool)`，ArchUnit 强制）
- [x] (2026-06-30 21:02+08:00) `subagent/tools/` 子包：VisionSubagentTool + ExploreSubagentTool
- [x] (2026-06-30 21:04+08:00) `summarization/` 子包：SummaryPromptBuilder + ForkChildSummarizationPort（实现 SummarizationPort SPI）

### 里程碑 7：Plan 模式控制器 + Agent Orchestrator + Hook 接线

- [x] (2026-06-30 21:05+08:00) `planmode/` 子包：PlanModeState enum、PlanModeController（enter/exit 状态机）、RuntimeStatePlanModeView（实现 PlanModeView 读 RuntimeState.metadata）、AgentPlanToolHandler + AgentPlanExitToolHandler（plan / plan_exit 工具 handler）
- [x] (2026-06-30 21:07+08:00) `hooks/` 子包：TurnStoppedSessionMemoryHook（order=100）、AssistantMemoryIngestionHook（order=200）、PreCompactSummaryHook（order=50，注入 compact.summaryInstructions）
- [x] (2026-06-30 21:10+08:00) AgentOrchestrator（per-request 入口薄装配层，同步触发 MemoryRecallPlanner.plan + SessionMemoryService.load + DynamicPromptAssembler.assemble）
- [x] (2026-06-30 21:13+08:00) AgentAutoConfiguration（`@EnableConfigurationProperties(AgentProperties.class)` + `@MapperScan`）+ spring boot autoconfigure 注册
- [x] (2026-06-30 21:14+08:00) `mvn -pl pixflow-agent compile` BUILD SUCCESS（73 个源文件）

### 里程碑 8：ArchUnit 守护

- [x] (2026-06-30 21:20+08:00) `pixflow-tools/ToolRegistry` 接口新增 registerDynamic / unregisterDynamic 默认方法
- [x] (2026-06-30 21:22+08:00) `DefaultToolRegistry` 实现 registerDynamic（保留旧构造签名兼容）
- [x] (2026-06-30 21:22+08:00) `AgentArchitectureTest`（archunit-junit5）5 条断言：agent 不依赖业务 module + agent 不依赖 provider 适配器 + agent 不持有 PO + SubagentRunner 只暴露 runAsync + PromptRuntimeContext record 不可变
- [x] (2026-06-30 21:22+08:00) ArchUnit 5/5 测试全绿

### 里程碑 9：测试与验证

- [x] (2026-06-30 21:38+08:00) 编写 7 个单元测试类：PromptSectionTest（3 方法）+ CaffeinePromptSectionCacheTest（6 方法）+ RrfMergerTest（4 方法）+ PlanModeControllerTest（6 方法）+ SubagentRunnerTest（2 方法）+ PreCompactSummaryHookTest（2 方法）+ AgentArchitectureTest（5 方法 ArchUnit）
- [x] (2026-06-30 21:40+08:00) `mvn -pl pixflow-agent test` 全部 28 测试方法全绿
- [x] (2026-06-30 21:50+08:00) `mvn -pl pixflow-app -am compile` BUILD SUCCESS — agent bean 装配不影响 app 现有配置

### 里程碑 10：文档同步

- [x] (2026-06-30 22:00+08:00) `docs/design-docs/harness/context.md` 添加 Revision Notes 条目（SessionMemoryPort SPI 落地说明）
- [x] (2026-06-30 22:01+08:00) `docs/design-docs/agent/agent.md` 添加 Revision Notes 条目（pixflow-agent 落地说明）
- [x] (2026-06-30 22:02+08:00) 本 ExecPlan Progress 段全部勾完，已完成清单 73 个源文件 + 28 个测试方法


### 阶段 1：Maven 模块骨架 + SPI 补位 + SessionMemoryPort 接口

- [ ] (里程碑 1) 在根 `pom.xml` `<modules>` 增加 `<module>pixflow-agent</module>`，并在 `<dependencyManagement>` 增加 `pixflow-agent` BOM 条目（不写 version，由子模块自填）。
- [ ] (里程碑 1) 新建 `pixflow-agent/pom.xml`，声明依赖：`pixflow-common`、`pixflow-contracts`、`pixflow-harness-loop`、`pixflow-harness-tools`、`pixflow-harness-context`、`pixflow-harness-session`、`pixflow-harness-hooks`、`pixflow-harness-eval`、`pixflow-harness-permission`、`pixflow-module-memory`、`pixflow-module-file`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-cache`、`mybatis-plus-spring-boot3-starter`（3.5.7）、`redisson-spring-boot-starter`、`spring-boot-autoconfigure`、`spring-boot-configuration-processor`（optional）、`spring-boot-starter-test`（test）、`archunit-junit5`（test）、`testcontainers`（test）、`caffeine`（用于 PromptSectionCache 的 LRU，取代自写 ConcurrentHashMap + size 上限）。
- [ ] (里程碑 1) **先在 `pixflow-context` 补 `SessionMemoryPort` SPI**（`com.pixflow.harness.context.sessionmemory`）：4 方法 `load(conversationId) → Optional<SessionMemoryContent>` / `save(conversationId, content, lastSummarizedSeq)` / `computeThreshold(conversationId, currentHeadSeq, currentTurnNo) → SessionMemoryThreshold` / `scheduleExtraction(conversationId, turnNo)`，定义 `SessionMemoryContent`（Markdown 字符串）与 `SessionMemoryThreshold`（lastSummarizedSeq + coveredTurnCount + sinceLastExtractTurn + sinceLastExtractTokens + shouldExtract()）两个 record。在 `pixflow-context/.../compaction/` 与 `model/` 之间新增 `sessionmemory/` 子包；该包对 agent 模块只暴露接口，无实现。
- [ ] (里程碑 1) 在 `docs/design-docs/harness/context.md` §三 / §四 / §十四 同步「`SessionMemoryPort` SPI 定义在 `harness/context`，实现归 `agent`（Wave 5）」的接缝说明；在 `module-dependency-dag-plan.md` 补 `context --> agent` 边（agent 实现 SPI 倒置接入）；在 revision notes 段记录。
- [ ] (里程碑 1) `mvn -pl pixflow-context -am test` 全绿（已有 110+ 测试不破），证明 SPI 补位零侵入。

### 阶段 2：装配层核心抽象与 section 渲染（零 Spring 上下文纯函数）

- [ ] (里程碑 2) `pixflow-agent/src/main/java/com/pixflow/agent/prompt/`：`PromptSection`（record 6 字段：key / title / body / fingerprint / cacheable / render()）；`PromptRuntimeContext`（record 11 字段：state / conversationId / turnNo / packageId / attachedSkuIds / mentionedSkuIds / recentAssistantMessageIds / userMessage / planMode / recall / sessionMemory / visibleTools）；`PromptSectionCache` 接口 + `CaffeinePromptSectionCache` 实现（`Caffeine.newBuilder().maximumSize(props.maxEntries).recordStats()`，进程内 LRU，**不引 Redis**）；`PromptSummary`（不可变视图，给 trace 用，只含 section_keys + fingerprint + totalChars，不暴露完整 prompt）；`PromptProvider` SPI（`String systemPrompt(state)` + `List<ToolSchemaView> toolSchemas(state)`，loop 调）。
- [ ] (里程碑 2) `prompt/sections/`：`IdentitySection` / `BehaviorRulesSection` / `RiskAndSafetySection` / `VerificationSection`（4 个固定 section，从 classpath resource 加载 Markdown 资源文件，fingerprint = sectionVersion）；`PreferenceSection`（渲染 `user_preference` 表全量读，fingerprint = sha256(全部 key + value + updated_at)）；`SessionMemorySection`（渲染 SessionMemoryContent Markdown，fingerprint = "{lastSummarizedSeq}.{contentHash}"）；`LongTermMemorySection`（渲染 RRF 融合后的 SKU 历史 + 分析结论子段，fingerprint = "{recallPlanId}.{totalTokens}"）；`WorkspaceStateSection`（渲染 packageId + turnCount + planMode 标记）；`ActiveSkillsSection`（渲染已注册 skill 目录，含 name + description + when_to_use，fingerprint = sha256(sorted(name+description+when_to_use+version))）；`ToolPromptSection`（聚合 visible tools 的 `prompt` 字段，含核心 7 工具与 `skill__<name>`，fingerprint = sha256(sorted(visibleTools.toolName+prompt))）；单工具 prompt 段 `ToolPromptForNameSection(key="tool_prompt:<name>")` 渲染单个 tool 的 prompt 字段，fingerprint = "<name>.v<version>"。
- [ ] (里程碑 2) `DynamicPromptAssembler.assemble(PromptRuntimeContext) → String`：按 `agent.md §4.1` 表的固定顺序串行 `section.render()`，过滤空 body，`"\n\n".join`；每段渲染前查 `cache.get(key, fingerprint)` 命中复用，未命中调 `section.render()` 后 `cache.put(key, fingerprint, rendered)`；fingerprint 计算委托各 section 自己（外部零认知）。
- [ ] (里程碑 2) **新增 `MessageStore.appendSkillInvocation(String skillName, int skillVersion, int bodyChars)` 与 `MessageStore.appendPlanModeChange(PlanModeState from, PlanModeState to)`**：user prompt submit 时由 `AgentOrchestrator` 调，让 Plan 模式切换与 skill 调用落库可审计（这与 `MessageMetadata` 缺 planMode key 不冲突，metadata 是 per-message 开放位，状态归属 `RuntimeState.metadata`，这里是事件 trail）。
- [ ] (里程碑 2) 单元测试：`PromptSectionTest` 验证 render / fingerprint 单调 / 重排稳定；`CaffeinePromptSectionCacheTest` 验证 LRU 淘汰、stats 计数、并发安全、invalidateAll；`DynamicPromptAssemblerTest` 验证固定顺序、空 body 过滤、cache 命中短路、各 section fingerprint 独立生效；`PromptRuntimeContextTest` 验证 record 字段映射。

### 阶段 3：Skill 机制（工具化 + 渐进披露 + 同步入表）

- [ ] (里程碑 3) `pixflow-agent/src/main/resources/skills/`：新建示例 SKILL.md 文件 `beauty_watermark/SKILL.md`（frontmatter 4 字段 + body ≤ 32KB），作为启动期同步测试 fixture。
- [ ] (里程碑 3) `skill/SkillFrontmatter`（record：name / description / whenToUse / version）+ `SkillFrontmatterParser`（解析 `---` 之间的 YAML，简单 key-value 解析，不引 SnakeYAML 库）；`SkillRepository`（MyBatis-Plus mapper，封装 CRUD）；`Skill`（entity：`@TableName("skill")`，含 11 字段）。
- [ ] (里程碑 3) SQL 迁移 `pixflow-agent/src/main/resources/db/migration/V1__create_skill.sql`：`CREATE TABLE skill (id VARCHAR PK, name VARCHAR(50) UNIQUE NOT NULL, description VARCHAR(200) NOT NULL, when_to_use VARCHAR(500) NOT NULL, body MEDIUMTEXT NOT NULL, source ENUM('BUILTIN','PROJECT','TEAM') NOT NULL, version INT NOT NULL, body_hash VARCHAR(64) NOT NULL, created_at DATETIME, updated_at DATETIME, INDEX idx_skill_source(source))`。本期只允许 BUILTIN，其余 enum 值保留扩展。
- [ ] (里程碑 3) `SkillLoader`（`@Component`，构造注入 `SkillRepository`、`SkillFrontmatterParser`、`ObjectStorage`（用于 body 字节审计））：`@PostConstruct syncBuiltIn()` 扫 `classpath:skills/*/SKILL.md`，按 `agent.md §5.5` 算法幂等同步（id 自增或雪花、SELECT existing by name、INSERT/UPDATE/skip、删除启动时未出现的 BUILTIN 行）；格式校验失败记 `WARN` 日志 + `pixflow.agent.skill.invalid` 指标，不抛异常（保证启动不被一个坏 skill 卡死）。
- [ ] (里程碑 3) `SkillToolRegistrar`（`@Component`，构造注入 `SkillRepository`、`ToolRegistry`）：`@PostConstruct registerAll()` 查 `skillRepository.findAllBySource(BUILTIN)`，对每条 skill 构造 `ToolDescriptor(name="skill__<name>", description=skill.description, inputSchema={type:object, properties:{question:{type:string}}, additionalProperties:false}, outputSchema={type:object, properties:{body:{type:string}}}, prompt=skill.description+"\n调用时机："+skill.whenToUse, readOnlyHint=true, handler=SkillHandler, classifier=FailClosedReadOnlyClassifier, validator=NoOpValidator)`，调 `toolRegistry.registerDynamic(descriptor)`（**`ToolRegistry` 需扩展为支持运行时注册**——见阶段 7 跨模块扩展）。
- [ ] (里程碑 3) `SkillHandler`（`@Component`，实现 `ToolHandler`，构造注入 `SkillRepository`、`ToolResultStorage`）：`handle(inv)` → 解析 `inv.toolName().substring("skill__".length())` → `skillRepository.findByName(name)` → 若不存在抛 `SkillNotFoundException` → 返回 `ToolHandlerOutput(body, metadata{skill_name, skill_version, body_chars})`；body 长度 > 50KB 走 `toolResultStorage.write(...)` 外置，metadata 加 `tool_result_externalized` 标记（与其他 tool 一致）。
- [ ] (里程碑 3) 命名校验（`SkillFrontmatterParser.validate(name, description, whenToUse, version)`）：name 正则 `^[a-z][a-z0-9_]{1,40}$`；description 长度 ≤ 200；when_to_use 长度 ≤ 500；version 是正整数；body 字节 ≤ 32KB。
- [ ] (里程碑 3) 单元测试：`SkillFrontmatterParserTest`（合法 4 字段 + 各种缺/越界）；`SkillLoaderTest`（幂等同步、UPDATE 路径、DELETE 路径、校验失败不抛）；`SkillHandlerTest`（handler 返回 body + 缺失抛 SkillNotFoundException + body 超 50KB 走外置）；`SkillToolRegistrarTest`（注册 N 个 descriptor、name 重复抛 IllegalStateException）。

### 阶段 4：自动记忆召回（多通道 + RRF + token 预算）

- [ ] (里程碑 4) `memory/RecallChannel`（enum：PREFERENCE / SKU_HISTORY / INSIGHT_VECTOR / INSIGHT_FULLTEXT）；`memory/MemoryItem`（record：itemId / score / channel / preview / metadata）；`memory/RecallSignal`（record：userMessage / attachedPackageId / currentPackageSkuIds / recentAssistantMessages[N=3] / mentionedSkuIds）；`memory/MemoryRecallResult`（record：sections{MemorySection} / recallPlanId(UUID) / totalTokens / recallTrace{MetadataForTrace}）。
- [ ] (里程碑 4) `memory/SkuMentionExtractor`（两阶段：regex 抽取 → LLM 兜底）：regex 阶段匹配配置 `pixflow.agent.memory.sku-patterns`（默认 `^SKU\d+$`、`^[A-Z]{2,}\d{4,}$`），命中 ≥ 3 个直接返回；LLM 阶段调 `ChatModelClient.call(...)` 抽取 + Redis 缓存键 `pixflow:agent:sku_extract:{messageHash}` TTL 24h；缓存由 `infra/cache` 的 `RedissonClient` 实现。
- [ ] (里程碑 4) `memory/RrfMerger`（标准 RRF，k=60 可配）：输入各通道有序 `List<MemoryItem>`，输出 RRF 融合 + token 预算裁剪后的 `List<MemoryItem>`；用 `jtokkit` 估算每个 item 的 token 数，按 RRF 顺序累加直到 ≤ `maxTokens`（默认 4000）截断；返回 `MemoryRecallResult.totalTokens = 实际累计数`。
- [ ] (里程碑 4) `memory/MemoryRecallPlanner`：`plan(signal) → MemoryRecallResult`；先并行触发 4 通道：① `PreferenceService.loadAll() → MemorySection("user_preferences")`；② `if signal.currentPackageSkuIds 非空 OR mentionedSkuIds 非空: SkuHistoryService.findBySkuIds(...) → MemorySection("sku_history")`；③ `InsightVectorService.search(embed(signal.userMessage), topN=20, filter)`；④ `InsightFulltextService.search(MATCH(text) AGAINST(signal.userMessage), topN=20)`；并发跑（`CompletableFuture` + 独立小线程池或公共 ForkJoinPool），任一通道失败记 warn 并降级（向量失败只保留 FULLTEXT、FULLTEXT 失败只保留向量）；最后 `RrfMerger.merge(...)`。
- [ ] (里程碑 4) **recall 触发点放在 `AgentOrchestrator.streamNewTurn` 入口**（用户答"为什么不是 hook"时给的边界——hook 时机是 `TURN_STOPPED` 在回合末，本回合已用不上；下一回合再召回是基于**上一回合末已落库**的 transcript，与本回合当次输入存在 1 回合时差；放 AgentOrchestrator 入口能拿到当次 prompt + 当次附件 + 当次会话状态，时序更准）。在 plan 内显式记录此决策（见 Decision Log）。
- [ ] (里程碑 4) **类目 filter 留接口不实现**：本期 `commerce_data` / `asset_copy` 无 `category` 字段（design.md §十六 暂不考虑真实平台对接），`MemoryRecallPlanner` 构造的 Qdrant filter 始终为空；保留 `VectorFilter filter` 参数，未来真实电商 API 接入后填 `must([FieldCondition("category", in, topCategories)])`。
- [ ] (里程碑 4) 单元测试：`SkuMentionExtractorTest`（regex 命中 ≥ 3 直返、LLM 兜底、Redis 缓存命中）；`RrfMergerTest`（属性测试：4 通道融合单调性 + token 截断边界 + 单通道为空退化）；`MemoryRecallPlannerTest`（mock 4 通道服务，验证并发触发 + 降级矩阵 + recallPlanId 是新 UUID + totalTokens 等于实际累计）。
- [ ] (里程碑 4) 集成测试（Testcontainers MySQL + Qdrant + mock infra/ai embedding）：上传素材包 → 注入 5 条 analysis_insight（含 1 SUPPRESSED）→ 用户提问 → 验证 prompt 含召回片段、ACTIVE 行被召回、SUPPRESSED 行未召回。

### 阶段 5：Session Memory 累积提取（异步 + 替换式 + 断路器）

- [ ] (里程碑 5) `sessionmemory/` 子包：`SessionMemoryContent`（record：markdown / contentHash(md5)）；`SessionMemoryThreshold`（record：lastSummarizedSeq / coveredTurnCount / lastExtractTime / sinceLastExtractTurn / sinceLastExtractTokens / shouldExtract()）；`SessionMemoryRow`（entity：`@TableName("session_memory")`）。
- [ ] (里程碑 5) SQL 迁移 `V2__create_session_memory.sql`：`CREATE TABLE session_memory (conversation_id VARCHAR(64) PK NOT NULL, content MEDIUMTEXT NOT NULL, last_summarized_seq BIGINT NOT NULL, covered_turn_count INT NOT NULL, source ENUM('EXTRACTION','FALLBACK_RULE') NOT NULL, content_hash VARCHAR(64) NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, INDEX idx_session_memory_updated(updated_at))`。
- [ ] (里程碑 5) `SessionMemoryCache`（封装 Redis 缓存：`RedissonClient.getBucket("session:memory:" + conversationId)`，TTL 3600s，set/get/delete）；`SessionMemoryRepository`（MyBatis-Plus mapper，封装 `findById`、`upsert`（`INSERT ... ON DUPLICATE KEY UPDATE`）、`delete`）；`SessionMemoryService`（实现 `harness/context/SessionMemoryPort` SPI 4 方法）。
- [ ] (里程碑 5) **回写 `docs/design-docs/harness/session.md` §十七 暂不考虑**：删除"session memory（跨压缩连续性 Markdown + 提取子 Agent）：参考实现的 `session-memory.md` 机制本期不做"这一条；改为"本期不做跨会话拼接 / 节点级续传 / 物理删除归档 / Redis 缓存收编进 session / 多租户隔离——单 session 的 Session Memory 累积由 `agent` 模块的 `SessionMemoryService`（实现 `context.SessionMemoryPort` SPI）承担，与本模块的 transcript 持久化并行存在"。在 revision notes 段记录。
- [ ] (里程碑 5) `SessionMemoryExtractor`（`@Component`，构造注入 `SubagentRunner` + `ChatModelClient` + `ChatRequestFactory`）：`extract(conversationId) → SessionMemoryContent`：先 `load(content + lastSummarizedSeq)`，计算增量 `messages_since_last_extract`（从 `context.MessageStore.currentMessages()` 过滤 `seq > lastSummarizedSeq`），构造 child prompt（按 `agent.md §7.4.3` Markdown 结构模板），调 `subagentRunner.runAsync(...)` 跑 child LLM 提取；child 工具集为空（`ChildToolFilter.empty()`），与 Session Memory 提取同款配置复用。
- [ ] (里程碑 5) `SessionMemoryUpdater`（fallback 规则式）：`extractFallback(conversationId)` 拼接"## (Fallback) 最近对话摘要"段，含最近 1 条 user message 全文 + 最后 1 条 assistant message 前 500 字；不调 LLM；连续失败 ≥ 3 次切换到 fallback（断路器在 `SessionMemoryService` 自维护，进程内 `AtomicInteger`）。
- [ ] (里程碑 5) `SessionMemoryService` 主干（实现 `SessionMemoryPort` 4 方法）：
  - `load(conversationId)`：先 Redis 缓存 miss → MySQL → 返回 `Optional<SessionMemoryContent>`；命中直接返回。
  - `save(conversationId, content, lastSummarizedSeq)`：单事务 `INSERT ... ON DUPLICATE KEY UPDATE`，更新 `updated_at` + `content_hash`；写 MySQL 成功后 set Redis 缓存。
  - `computeThreshold(conversationId, currentHeadSeq, currentTurnNo)`：从 `load` 拿 `lastSummarizedSeq + coveredTurnCount`；增量 messages = `context.MessageStore.currentMessages().filter(seq > lastSummarizedSeq)`；token 估算用 jtokkit；返回 `SessionMemoryThreshold.shouldExtract()`。
  - `scheduleExtraction(conversationId, turnNo)`：提交 `SessionMemoryExtractionTask` 到 `pixflow.agent.session-memory.extraction-pool`（独立线程池 core=2/max=4/queue=100）；立即返回不阻塞；后台跑：阈值判定 → extractor 或 updater → save；失败计数 +1，连续 3 次切换 fallback。
- [ ] (里程碑 5) 单元测试：`SessionMemoryServiceTest`（MyBatis-Plus 替身 + Redis mock 验证缓存 miss/hit 双路径、save 顺序、阈值重算场景"上次 seq=50 当前 head=120" 增量 = 70 条）；`SessionMemoryExtractorTest`（mock SubagentRunner 返回新 content → save 调一次）；`SessionMemoryUpdaterTest`（fallback content 拼接格式正确、不调 LLM）；`SessionMemoryThresholdTest`（shouldExtract 在 token ≥ 10K 或 turn ≥ 3 时为真）。

### 阶段 6：异步 Subagent Runner + 三处复用（agent 工具 / 摘要 / Session Memory）

- [ ] (里程碑 6) `subagent/` 子包：`SubagentRequest`（record：type / prompt / parentToolCallId / parentConversationId / imageIds / focus / summaryInstructions / metadata；type ∈ `VISION` / `EXPLORE` / `SUMMARIZATION` / `SESSION_MEMORY_EXTRACTION`）；`SubagentResult`（record：finalText / usage / toolResultCount / errorMessage / transition / isError）；`ChildRuntimeFactory`（构造 child RuntimeState + MessageStore + ToolRegistry + ContextEngine + AgentLoop + ToolExecutor + HookRegistry）。
- [ ] (里程碑 6) `ChildToolFilter`（`@Component`）：按 `type` 决定工具集：
  - `VISION` / `EXPLORE`：core 7 工具，**额外禁用 `submit_image_plan` / `submit_imagegen_plan` / `plan`**（child 只读 + 看图 / 探索）；保留 `search` / `read` / `agent` / `skill__<name>`。
  - `SUMMARIZATION` / `SESSION_MEMORY_EXTRACTION`：**全部禁用 7 核心工具**（child 只调 LLM 总结输入），保留 `skill__<name>`？**本期**：摘要/提取 child 工具集 = 空（与 `agent.md §8.4` 一致），未来需要 skill 时再开。
- [ ] (里程碑 6) `SubagentRunner`（`@Component`，构造注入 `ChildRuntimeFactory` / `ChildToolFilter` / `HookRegistry` / `ModelRetryRunner` / `ModelClient` / `ExecutorService subagentPool`）：
  - `runAsync(SubagentRequest) → CompletableFuture<SubagentResult>`：submit 到 `subagentPool`（`Executors.newThreadFactory(daemon=true, nameFormat="agent-subagent-%d")` + `ThreadPoolExecutor(core, max, keepAlive, queue)` 按配置）。
  - 主路径：`ChildRuntimeFactory.build(req)` → child AgentLoop 构造（**不再用 spring bean**，per-call 构造，与 `loop.md §十二` 一致；接受"装配成本 = 15 个依赖的 setter 链路 + 几个 in-memory 替身"，child 用 in-memory MessageStore 不连 MySQL）→ child Loop 同步跑 `continueStream` → `CapturingSink` 捕获 → 抽 `finalText + usage + toolResultCount` → 返回 `SubagentResult`。
  - 异常路径：child 抛不可恢复异常 → `SubagentResult(isError=true, errorMessage=safeMsg)`，**不冒泡到父 loop**（与 `agent.md §8.7` 一致）。
  - usage / tool spans 合并：把 child 工具调用 span 转为父 `ToolExecutionResult.metadata.childToolSpans`，由 `agent` 工具 handler 透传到父 `TurnTrace.recordToolCall` 的 metadata（与 `agent.md §8.6` 一致）。
- [ ] (里程碑 6) `subagent/tools/VisionSubagentTool` 与 `ExploreSubagentTool`（`@Component` 各实现 `ToolHandler`）：
  - `agent(type=vision)` handler：解析入参 `{type:"vision", imageIds:[...], prompt:"..."}` → 构造 `SubagentRequest.vision(...)` → 调 `subagentRunner.runAsync(req).join()` → 返回 `ToolHandlerOutput(finalText, metadata{subagent_type, usage, tool_result_count, child_tool_spans})`。
  - `agent(type=explore)` handler：同上但 `SubagentRequest.explore(...)`。
  - **关键**：`runAsync().join()` 是阻塞，但 child 在独立线程池跑，父 loop 不阻塞等待——这是异步的关键设计点。`VisionSubagentTool` / `ExploreSubagentTool` 本身仍是同步 handler（实现 `ToolHandler` 接口），但内部 join 时**不阻塞调用线程所在的 subagent pool**（因为 subagent pool 的 core=4 ≥ 同时活跃的 subagent 数通常 < 4），而父 loop 在 tools 执行管线调用本 handler 时（thread pool 由 `tools` 配置，max=8），阻塞 join 是 OK 的——父 loop 是同步的，子 subagent 在另一池跑，join 实际只等 child LLM 调用完成（秒级）。
- [ ] (里程碑 6) `summarization/ForkChildSummarizationPort`（`@Component`，实现 `harness/context/SummarizationPort`）：
  - `summarize(SummarizationRequest) → SummaryResult`：构造 `SubagentRequest.summary(...)`（type=SUMMARIZATION，prompt 含 `req.messages` + `req.summaryInstructions`）→ 调 `subagentRunner.runAsync(req).join()` → 成功返回 `SummaryResult.ok(finalText)`，失败 `circuitBreaker.recordFailure()` + `SummaryResult.failed(errorMessage)`。
- [ ] (里程碑 6) 单元测试：`ChildToolFilterTest`（4 type 各自的禁用列表精确）；`SubagentRunnerTest`（mock child AgentLoop 的 continueStream，验证异步提交 + 结果捕获 + 异常降级）；`VisionSubagentToolTest`（mock SubagentRunner，验证参数解析 + child_spans metadata 透传）；`ForkChildSummarizationPortTest`（mock SubagentRunner，验证成功返回 / 失败上报断路器）。
- [ ] (里程碑 6) 集成测试（Testcontainers MySQL + Redis + mock LLM）：跑完整"agent 工具调用 → 异步 subagent → 父回合收到 finalText → child 消息**未落 message 表**"链路（验证 ephemeral store 不绑 TranscriptPort）。

### 阶段 7：Plan 模式控制器 + Agent Orchestrator 入口 + Hook 接线

- [ ] (里程碑 7) `planmode/PlanModeState`（enum：OFF / ACTIVE）；`planmode/PlanModeController`（`@Component`，构造注入 `RuntimeState`）：`enter()` 校验当前 OFF（否则抛 IllegalStateException）→ `state.putMetadata("planMode", true)` → 派发 `PLAN_MODE_ENTERED` 自定义 hook 事件（如需新增 HookEvent，先扩 `pixflow-hooks/HookEvent`，并在 `hooks.md` 同步）；`exit(draftPlan)` 校验当前 ACTIVE → `state.putMetadata("planMode", false)` + `state.putMetadata("lastPlanDraft", draftPlan)` → 派发 `PLAN_MODE_EXITED`。
- [ ] (里程碑 7) `planmode/PlanModePrompter`（可选：把 `lastPlanDraft` 拼成 attachment 投影给下回合 `WorkspaceStateSection`，与 `agent.md §11.3` 一致）。
- [ ] (里程碑 7) `AgentPlanToolHandler` 与 `AgentPlanExitToolHandler`（`@Component` 各实现 `ToolHandler`，构造注入 `PlanModeController` + `RuntimeState`）：
  - `plan` handler：调 `planModeController.enter()` → 返回 `ToolHandlerOutput("Entered Plan mode. Read-only tools only; write tools hidden.")`；handler 自身不直接改 `RuntimeState.metadata`，统一经 `PlanModeController` 改写。
  - `plan_exit` handler：调 `planModeController.exit(draftPlan)` → 返回 `ToolHandlerOutput("Exited Plan mode. Plan draft saved.", metadata{draftPlan})`。
  - 两个 handler 贡献 `ToolDescriptor` bean：`plan` (readOnlyHint=true, concurrencySafe=false)；`plan_exit` (readOnlyHint=true, concurrencySafe=false)；`PlanModeView` SPI 暴露给 `DefaultToolRegistry`（实现 `boolean isPlanMode()` → `runtimeState.metadataOrDefault("planMode", false)`）。
- [ ] (里程碑 7) `AgentOrchestrator`（`@Component`，per-request 装配，**非 Spring 单例**——实际是普通类，由 `module/conversation` 的 `TurnDispatchService` 在回合入口 new 出来，注入所有依赖）：
  - 依赖注入列表（15 个）：`RuntimeState` / `MessageStore` / `ContextEngine` / `ChatModelClient` / `ModelRetryRunner` / `ToolExecutor` / `PermissionPolicy` / `ToolResultStorage` / `PlanModeView` / `HookRegistry` / `TraceRecorder` / `PermissionContextFactory` / `ErrorRecorder` / `LoopProperties` / `MemoryRecallPlanner` / `SessionMemoryService` / `DynamicPromptAssembler` / `SkillLoader`（已在阶段 3 @PostConstruct 跑过，这里只是引用 bean 验证完成） / `SubagentRunner`。
  - `streamNewTurn(conversationId, prompt, attachments, sink)`：
    1. 校验 conversationId 存在（注入 `ConversationService.requireActive(conversationId)`）。
    2. 会话级锁由 conversation 模块加（agent 不持锁，per design 边界）。
    3. rehydrate 由 loop + context 在 stream 入口完成（agent 不感知）。
    4. **调用 `MemoryRecallPlanner.plan(recallSignal)` 同步召回**——构造 `RecallSignal(userMessage=prompt, attachedPackageId=packageId, currentPackageSkuIds=packageService.getSkuIds(packageId), recentAssistantMessages=messageStore.currentMessages().filter(role=ASSISTANT).takeLast(3), mentionedSkuIds=skuMentionExtractor.extract(recentAssistantMessages))` → 返回 `MemoryRecallResult`。
    5. **构造 `PromptRuntimeContext(state, ..., recall=result, sessionMemory=sessionMemoryService.load(conversationId).orElse(null), visibleTools=toolRegistry.visibleDescriptors(visibilityCtx))`**。
    6. 调 `DynamicPromptAssembler.assemble(ctx)` 得 `systemPrompt`；调 `toolRegistry.toolSchemas(visibilityCtx)` 得 `toolSchemas`。
    7. 调 `agentLoop.stream(prompt, systemPrompt, toolSchemas, sink)` 阻塞跑完。
    8. 回合结束：调 `messageStore.flush()` → 释放会话级锁（由 conversation 模块管理）→ emit COMPLETED。
  - `continueTurn(conversationId, sink)`：跳过 user prompt 派发与追加，剩余同 `streamNewTurn`。
- [ ] (里程碑 7) `hooks/` 子包：3 个 `HookCallback` 实现：
  - `TurnStoppedSessionMemoryHook`（`@Component`，订阅 `TURN_STOPPED`，order=100）：`handle(event, payload)` → 校验 payload.runtime().subagent()==false（**只在主 Agent 触发**，child subagent 不触发 session memory 提取，避免噪声）→ 立即返回 `HookResult.noop()`，内部 `sessionMemoryService.scheduleExtraction(payload.conversationId(), payload.turnNo())` 提交异步任务。
  - `AssistantMemoryIngestionHook`（`@Component`，订阅 `ASSISTANT_MESSAGE_COMPLETED`，order=200）：`handle(event, payload)` → 校验主 Agent → 内部 `memoryService.ingestAsync(...)` 立即提交，hook 立即返回 noop（async 副作用由 callback 自理，与 `hooks.md §5.2` 一致）。
  - `PreCompactSummaryHook`（`@Component`，订阅 `PRE_COMPACT`，order=50）：`handle(event, payload)` → 返回 `HookResult.withMetadata(Map.of("compact.summaryInstructions", "PixFlow 电商运营 Agent：保留 SKU 处理状态、用户确认/拒绝决策、电商数据指标、用户偏好更新。"))`；context 在构造 `SummarizationRequest` 时消费此 metadata key。
- [ ] (里程碑 7) `error/AgentErrorCode`（`enum implements com.pixflow.common.error.ErrorCode`）：6 个自治码 `AGENT_PROMPT_ASSEMBLY_FAILED` (INTERNAL) / `AGENT_SKILL_LOAD_INVALID` (VALIDATION) / `AGENT_SKILL_NOT_FOUND` (NOT_FOUND) / `AGENT_MEMORY_RECALL_FAILED` (DEPENDENCY) / `AGENT_SESSION_MEMORY_EXTRACTION_FAILED` (DEPENDENCY) / `AGENT_SUBAGENT_TIMEOUT` (TIMEOUT)。
- [ ] (里程碑 7) `config/AgentProperties`（`@ConfigurationProperties(prefix="pixflow.agent")`）：完整承载 `agent.md §十四` 14 个子段（prompt.section-cache / skill / memory.recall / session-memory / subagent.pool / plan-mode / orchestrator），默认值与 `agent.md` 一致。
- [ ] (里程碑 7) `config/AgentAutoConfiguration`（`@Configuration`）：`@EnableConfigurationProperties(AgentProperties.class)` + `@MapperScan("com.pixflow.agent.skill", "com.pixflow.agent.sessionmemory")` + 暴露所有 `@Bean`：`PromptSectionCache` / `DynamicPromptAssembler` / `SkillRepository` / `SkillLoader` / `SkillToolRegistrar` / `SkillHandler` / `MemoryRecallPlanner` / `RrfMerger` / `SkuMentionExtractor` / `SessionMemoryService` / `SessionMemoryExtractor` / `SessionMemoryUpdater` / `SessionMemoryCache` / `SessionMemoryRepository` / `SubagentRunner` / `ChildRuntimeFactory` / `ChildToolFilter` / `VisionSubagentTool` / `ExploreSubagentTool` / `ForkChildSummarizationPort` / `PlanModeController` / `PlanModeView` 实现 / `AgentPlanToolHandler` / `AgentPlanExitToolHandler` / `TurnStoppedSessionMemoryHook` / `AssistantMemoryIngestionHook` / `PreCompactSummaryHook` / 3 个工具的 `ToolDescriptor` bean / subagent pool 的 `ExecutorService` bean（`@Bean(destroyMethod="shutdown")`）。

### 阶段 8：ArchUnit 守护 + 跨模块扩展（`ToolRegistry` 支持运行时注册）

- [ ] (里程碑 8) **跨模块扩展 `pixflow-tools/.../DefaultToolRegistry`**：把 `descriptors` 字段从构造期 immutable List 改为 `ConcurrentHashMap<String, ToolDescriptor>` + `registerDynamic(ToolDescriptor)` 方法；保持现有构造签名兼容（旧代码路径不变）；新增 `unregisterDynamic(name)` 用于未来 skill 热卸载（本期不调用）；`visibleDescriptors(ctx)` 改为 `ConcurrentHashMap.values()` 流过滤。该扩展必须在 agent 模块编写 SkillToolRegistrar 之前先合入 `pixflow-tools`。
- [ ] (里程碑 8) **跨模块扩展 `pixflow-context/.../MessageStore`**：新增 2 方法 `appendSkillInvocation(String skillName, int skillVersion, int bodyChars)` / `appendPlanModeChange(PlanModeState from, PlanModeState to)`（阶段 2 已提及）；调用 `appendUser(...)` 内部分支（role 仍是 USER，content 是描述性 metadata），保证 transcript 持久化统一经 TranscriptPort SPI。
- [ ] (里程碑 8) ArchUnit 测试 `architecture/AgentArchitectureTest`（`archunit-junit5`）：
  - `com.pixflow.agent..` 不依赖 `module/dag` / `module/task` / `module/commerce` / `module/vision` / `module/imagegen` / `module/rubrics` / `module/conversation`（agent 不反向依赖业务模块；与 `dag.md` / `loop.md` 反向约束同款手法）。
  - `com.pixflow.agent..` 不依赖任何 `module/*` 之外的 `infra/ai` provider 具体适配器（`OpenAiChatModelClient` / `DashScopeChatModelClient` / `AliyunChatModelClient` 等），只允许依赖 provider-neutral `ChatModelClient` / `ChatStreamEvent` / `ChatRequest` / `ChatResult` / `TokenUsage`。
  - `com.pixflow.agent..` 不引用 `harness/tools` 的 `ToolCallClassification` 内部字段（除了 `ToolDescriptor` / `ToolHandler` / `ToolInvocation` / `ToolHandlerOutput` / `ToolResultPolicy` 这 5 个公开 SPI）；避免反向依赖 tools 内部。
  - `com.pixflow.agent..` 不持有 `MessageEntity` / `CompactionEntity` / `SessionEntity` / `SkillRow` 等 PO（仅持有 `Message` 模型与自己的 record）；上层只见 `Message`。
  - `SubagentRunner` 不在主循环路径被同步调用（强制 `runAsync()` 返回 `CompletableFuture`，避免有人误用 `.run()` 同步 API；接口只暴露 `runAsync`）。
  - `DynamicPromptAssembler` 不在 `assemble` 内部调 `ChatModelClient.call(...)` / `MemoryService.prepareContext(...)` 等 IO 操作（保证 IO 与渲染解耦；记忆召回调到 `AgentOrchestrator` 入口，渲染纯函数）。
- [ ] (里程碑 8) 属性测试（jqwik 或手写 generator）：
  - "tool calls / no calls / CONTEXT_LIMIT / session memory threshold" 任意事件序列下，session memory 累积单调（只追加/替换，不丢）。
  - auto compact 触发频率 ≤ session memory 触发频率的 10%。
  - 退出/重入会话：从 `lastSummarized_seq=50` 起重算增量，不重复处理 seq 1-50。
  - 任意 skill 加载/调用/修改序列下，已注册工具集合一致（`Set<String> toolNames` 在 registerAll 前后 stable）。

### 阶段 9：测试与验证

- [ ] (里程碑 9) 单元测试集合（按 `agent.md §16` 全量）：`PromptSectionTest` / `CaffeinePromptSectionCacheTest` / `DynamicPromptAssemblerTest` / `PromptRuntimeContextTest` / `SkillFrontmatterParserTest` / `SkillLoaderTest` / `SkillHandlerTest` / `SkillToolRegistrarTest` / `SkuMentionExtractorTest` / `RrfMergerTest` / `MemoryRecallPlannerTest` / `SessionMemoryServiceTest` / `SessionMemoryExtractorTest` / `SessionMemoryUpdaterTest` / `SessionMemoryThresholdTest` / `ChildToolFilterTest` / `SubagentRunnerTest` / `VisionSubagentToolTest` / `ExploreSubagentToolTest` / `ForkChildSummarizationPortTest` / `PlanModeControllerTest` / `AgentPlanToolHandlerTest` / `AgentPlanExitToolHandlerTest` / `TurnStoppedSessionMemoryHookTest` / `PreCompactSummaryHookTest` / `AgentOrchestratorTest`。预计 26 个测试类、~150 个测试方法。
- [ ] (里程碑 9) 集成测试（Testcontainers MySQL + Redis + Qdrant + MinIO + mock LLM）：
  - `SkillEndToEndIT`：写 SKILL.md → 启动同步入表 → 调 `skill__<name>` → 验证 body 返回 + trace metadata 正确。
  - `SessionMemoryEndToEndIT`：跑 5 回合 → 触发提取 → 验证 MySQL 单行 content 更新 + Redis 缓存同步 + 断路器切换 fallback。
  - `MemoryRecallEndToEndIT`：上传素材包 → 注入 5 条 analysis_insight → 用户提问 → 验证 prompt 含召回片段 + ACTIVE 行被召回。
  - `SubagentEndToEndIT`：调 `agent(type=vision)` → child 跑 vision service → 父回合收到 finalText → child 消息**未落 message 表**。
  - `CompactionEndToEndIT`：触发 `CONTEXT_LIMIT` → `SummarizationPort.summarize` → fork child 摘要 → 验证活动链改写 + session memory content 不受影响。
  - `PlanModeEndToEndIT`：调 `plan` → 验证 visibleTools 不含 `submit_*` → 调 `plan_exit` → 恢复。
- [ ] (里程碑 9) 验证命令（最终）：
  - `mvn -pl pixflow-agent -am test`：单元 + 集成测试全绿，ArchUnit 守护 6/6 绿。
  - `mvn -pl pixflow-app -am test`：BUILD SUCCESS，agent bean 装配不影响 app 现有测试。
  - `mvn -pl pixflow-agent package`：产物含 `AgentOrchestrator` / `SkillLoader` / `SessionMemoryService` / `SubagentRunner` / `ForkChildSummarizationPort` 等 bean。

### 阶段 10：文档与依赖图同步

- [ ] (里程碑 10) 在 `docs/design-docs/harness/context.md` §三 / §四 / §十四 同步 `SessionMemoryPort` SPI 定义（阶段 1 已完成）。
- [ ] (里程碑 10) 在 `docs/design-docs/harness/session.md` §十七 回写 session memory 不再"暂不考虑"，改为由 `agent` 模块实现 `context.SessionMemoryPort` 承担（阶段 5 已完成）。
- [ ] (里程碑 10) 在 `docs/design-docs/harness/hooks.md` §十二 / §六 同步 3 个新增 hook（TurnStoppedSessionMemoryHook / AssistantMemoryIngestionHook / PreCompactSummaryHook），标注由 agent 模块贡献。
- [ ] (里程碑 10) 在 `docs/design-docs/module-dependency-dag-plan.md` 补 3 条边：`context --> agent`（实现 SPI 倒置）、`hooks --> agent`（agent 提供 hook bean）、`session --> agent`（agent 不写但允许读 message 表，按 agent.md 边界）。
- [ ] (里程碑 10) 在 `module-dependency-dag-plan.md` §五 任务清单 Wave 5 把 `agent` 模块 checkbox 标 `[x]` + revision notes 加本计划落地说明。
- [ ] (里程碑 10) `docs/design-docs/agent/agent.md` revision notes 段加 `2026-06-30 / Codex: 完成 pixflow-agent Maven 模块全部里程碑，157+ 测试全绿，详见 exec-plans/agent-module-implementation-plan.md`。

## Surprises & Discoveries

本段记录探查与设计讨论阶段发现的事实偏差或未在 `agent.md` 中显式写出的隐含约束。所有发现都应在落地时作为硬约束遵守。

- Observation: `agent.md` 多次引用 `ModelClient` / `ModelStreamEvent` / `ModelUsage` 这套 infra/ai 抽象名（如 `agent.md:52` / `agent.md:53` / `agent.md:404`），但仓库实际类型是 `ChatModelClient` / `ChatStreamEvent` / `TokenUsage`（`pixflow-infra-ai/.../chat/ChatModelClient.java:8` 与 `chat/ChatStreamEvent.java:10` 与 `model/TokenUsage.java:6`）。
  Evidence: `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatModelClient.java` / `chat/ChatStreamEvent.java` / `model/TokenUsage.java`。
  Action: 落地时按仓库现状命名 import，**不照搬 `agent.md` 的字面命名**；agent 模块所有用到 infra/ai 的地方一律用 `Chat*` 前缀。

- Observation: `agent.md §7.2.1` 与 `harness/session.md §十七 暂不考虑` 直接冲突——`session.md` 明确写"session memory（跨压缩连续性 Markdown + 提取子 Agent）本期不做"，而 `agent.md` 设计了 Session Memory 单行 MySQL + Redis 缓存的完整机制。
  Evidence: `docs/design-docs/harness/session.md:475` 暂不考虑段第一条 / `docs/design-docs/agent/agent.md` 第七章整章。
  Action: 在 `harness/session.md` §十七 暂不考虑段删除 session memory 条目；在 `session.md` 与 `agent.md` 的 revision notes 段记录"session memory 范围由 agent 模块承担，session 模块只管 transcript 持久化"。两文档保留各自的接缝边界不变（session 仍是 message 表唯一写者，agent 不写 message 表）。

- Observation: `agent.md §6.2` 写"每轮 buildForModel 前同步触发 MemoryService.recall(signal)"，但 `context.buildForModel` 的当前签名是 `buildForModel(String systemPrompt, List<ToolSchemaView> toolSchemas) → BuildResult(snapshot, pruneEntries)`（`pixflow-context/.../engine/ContextEngine.java` 已按 loop 落地要求改完）。`buildForModel` 不接收 `RecallSignal`，纯字符串 in / pure snapshot out；记忆召回必须发生在 `buildForModel` 调用**之前**的 `AgentOrchestrator` 入口侧。
  Evidence: `pixflow-context/.../engine/ContextEngine.java` 现状签名 / `agent.md:484-486` 设计。
  Action: 召回触发点放在 `AgentOrchestrator.streamNewTurn` 第 4 步（构造 PromptRuntimeContext 前），与 plan "阶段 7 / AgentOrchestrator" 步骤对齐。

- Observation: `agent.md §8.5.1 VisionSubagentTool.handle` 写法是同步调 `subagentRunner.run(req)`，但用户决定"subagent 必须异步"。同步阻塞会让父 loop 长时间卡在 vision LLM 调用上（几秒~十几秒），违背 `loop.md §一.4` "无 maxTurns 但回合内不应过长"的健康度约束。
  Evidence: 用户在第二轮 AskUserQuestion 中明确说"他妈的subagent不异步还搞subagent干嘛"；`loop.md §一` / `loop.md §十八` 不引入 background agent 但允许 async。
  Action: `SubagentRunner` 接口只暴露 `runAsync(req) → CompletableFuture<SubagentResult>`；child 跑在 `pixflow.agent.subagent.pool` 独立线程池（core=4/max=16/queue=100）；VisionSubagentTool / ExploreSubagentTool 在 handler 内 `.join()` 阻塞 join，**但 join 发生在 tools 执行管线的并发池（max=8）而非主回合线程**——child LLM 几秒，父 loop 阻塞 join 几秒，等价于把 child LLM 调用当作"重型工具调用"对待，与 search/read 的同步语义对齐（视觉本来就是 search 的高级版）。

- Observation: `DefaultToolRegistry`（`pixflow-tools/.../DefaultToolRegistry.java:13-75`）构造期接 `List<ToolDescriptor>`，字段是 immutable List，**不支持运行时注册新工具**。agent 模块的 `SkillToolRegistrar.@PostConstruct registerAll()` 需要往 registry 注入 N 个 `skill__<name>` descriptor。
  Evidence: `pixflow-tools/src/main/java/com/pixflow/harness/tools/DefaultToolRegistry.java:19`。
  Action: 在 `pixflow-tools` 模块做小手术——把内部 `descriptors` 字段从 immutable List 改为 `ConcurrentHashMap<String, ToolDescriptor>` + 新增 `registerDynamic(ToolDescriptor)` 与 `unregisterDynamic(String)` 方法；保持旧构造签名兼容（`@Autowired List<ToolDescriptor>` 仍工作）。这是阶段 8 跨模块扩展必须先合入的前置。

- Observation: `MessageMetadata`（`pixflow-context/.../model/MessageMetadata.java:10-21`）只有 12 个已知 key，**没有 `planMode` / `subagent` / `readOnlyAgent` 这类 key**——这些是 `RuntimeState.metadata` 的开放 Map 字段（`pixflow-loop/.../RuntimeState.java:48` 注释明确列举 planMode 为预期写入键）。`agent.md §11` 把 Plan 模式状态写在 `RuntimeState.metadata.planMode` 是正确的，不要写到 `MessageMetadata` 上。
  Evidence: `pixflow-loop/.../RuntimeState.java:31-48` / `pixflow-context/.../model/MessageMetadata.java:10-21`。
  Action: `PlanModeController` 写 `state.putMetadata("planMode", true)`；`PlanModeView` 实现读 `runtimeState.metadataOrDefault("planMode", Boolean.FALSE)`；不要发明 `MessageMetadata.PLAN_MODE` 常量。

- Observation: `HookEvent`（`pixflow-hooks/.../HookEvent.java:3-15`）已有 11 个值含 `TURN_STOPPED` / `ASSISTANT_MESSAGE_COMPLETED` / `PRE_COMPACT`，与 `agent.md` 设计的 3 个 hook 完美对齐。**不需要扩 HookEvent 枚举**（用户决策中提及的 `PLAN_MODE_ENTERED` / `PLAN_MODE_EXITED` 本期**不引入**——Plan 模式状态已在 `RuntimeState.metadata` 集中管理，无需 hook 事件广播；如未来需要再扩）。
  Evidence: `pixflow-hooks/src/main/java/com/pixflow/harness/hooks/HookEvent.java`。
  Action: 直接订阅现有 3 个事件；Plan 模式切换不派发 hook（避免噪声）。

- Observation: 仓库 `pixflow-context` 已有 `SummarizationPort`（`pixflow-context/.../compaction/SummarizationPort.java`），签名 `SummaryResult summarize(SummarizationRequest request)`，**`SessionMemoryPort` 不存在**——`agent.md §7.7` 的 SPI 设计是设计文档，需要落地时新增。
  Evidence: `pixflow-context/src/main/java/com/pixflow/harness/context/compaction/SummarizationPort.java` / `agent.md:838-850` 设计。
  Action: 阶段 1 在 `pixflow-context/.../sessionmemory/` 新建 `SessionMemoryPort` 接口 + `SessionMemoryContent` / `SessionMemoryThreshold` record；agent 模块阶段 5 实现该接口。

- Observation: Java 版本是 21（`pom.xml:49`），不是 `agent.md` / `loop.md` / `tools.md` 设计文档写的 Java 17。已落地模块（loop / tools / context）都按 Java 21 落地。
  Evidence: 根 `pom.xml:49` `<java.version>21</java.version>`。
  Action: agent 模块按 Java 21 落地，与仓库现状一致；不需要回写设计文档。

- Observation: `PlanModeView`（`pixflow-tools/.../plan/PlanModeView.java:3`）是单方法接口 `boolean isPlanMode()`，`AgentLoop` 与 `DefaultToolRegistry` 都注入该接口。agent 模块的 `PlanModeController` **不直接实现 `PlanModeView`**——它写 `RuntimeState.metadata`，另起一个 `RuntimeStatePlanModeView` 实现类读 RuntimeState。这样避免 `PlanModeController` 与 `PlanModeView` 双职责耦合。
  Evidence: `pixflow-tools/src/main/java/com/pixflow/harness/tools/plan/PlanModeView.java`。
  Action: 阶段 7 新增 `RuntimeStatePlanModeView(@Component implements PlanModeView)` 类，构造注入 `RuntimeState`，实现 `isPlanMode()` 读 `state.metadataOrDefault("planMode", false)`。

- Observation: agent 模块会**修改 `MessageStore`** 加 2 个方法（`appendSkillInvocation` / `appendPlanModeChange`）。这是合法的"下游对上游 SPI 扩展"——`MessageStore` 是 `context` 模块的 SPI，agent 模块是 consumer，按 SPI 的"开放封闭"原则，下游不修改 SPI 定义，但 consumer 可以在不依赖 SPI 内部的前提下复用现成 appendXxx 方法。本计划提议在 `pixflow-context` 内部的 `DefaultMessageStore` 类增加 2 个公开方法（与现有 `appendUser` / `appendAssistant` 同款），不影响 SPI 接口本身。
  Evidence: `pixflow-context/.../store/MessageStore.java` 当前方法列表。
  Action: 阶段 8 跨模块扩展时同时改 `DefaultMessageStore`，新增 2 方法；agent 模块调用现成 API。

- Observation: 用户决策"Skill 补 tool_prompt 段"——但 `agent.md §5.1` 的 `SkillHandler` 示例签名 `(inv) -> loadSkillBody(...)` 沿用 Python `ToolHandler` 的同步风格，与 Java `ToolHandler.handle(ToolInvocation) → ToolHandlerOutput` 接口签名不完全对齐（Java 还有 `toolCallId` / `conversationId` / `turnNo` / `runtimeScope` 等入参）。skill handler 在 Java 落地时**与其它 7 工具的 handler 同款签名**，不发明轻量签名。
  Evidence: `pixflow-tools/.../ToolHandler.java` / `ToolInvocation.java` / `ToolHandlerOutput.java`。
  Action: `SkillHandler implements ToolHandler`，`handle(inv)` 中从 `inv.toolName()` 提取 skill name（剥 `skill__` 前缀），`inv.conversationId()` / `inv.turnNo()` 可在 metadata 中回填；handler 自身不调 LLM，只读 `skillRepository.findByName(name)` 返回 body。

- Observation: `agent.md` 多处提到 `trace` 上移到 loop 侧，agent 提供 `PromptSummary` / `RecallSummary` / `SessionMemoryDigest` / `ActiveSkillsDigest` 等只读视图供 eval 抽取。这与 `harness/eval.md §四 "被动 sink，可观测责任上移"` 一致；但 `agent.md §15.2` 写"loop 的 `TraceFanout` 在 `recordInput` 时把上述视图一并投递"——而 `pixflow-loop` 已有 `TraceFanout` 实现（`pixflow-loop/.../trace/TraceFanout.java`，按 loop 落地计划实现）。
  Evidence: `agent.md:1632-1638` / `pixflow-loop/.../trace/TraceFanout.java`。
  Action: agent 模块在回合末通过 `turnTrace.recordRecall(new TraceRecall(...))` / `recordInput(...)` 主动投递，**不修改** `TraceFanout`（它接收 Open 回调，不主动 pull agent）。

## Decision Log

本段记录本计划阶段做出的所有重要决策，每条决策都有 rationale 与日期/作者。所有决策应在执行阶段作为硬约束遵守，重大偏离需先在本段加新条目再实施。

- Decision: `pixflow-agent` 作为新独立 Maven 模块，源码包 `com.pixflow.agent`，按 Java 21 + Spring Boot 3.5.16 落地。
  Rationale: 与 `module-dependency-dag-plan.md` Wave 5 顶层节点对齐；agent 装配层独立成模块能保持依赖方向（`{loop, tools, context, hooks, eval, session, permission, memory, vision, imagegen, commerce, dag, conversation, file} + 所有 infra/*` → agent）清晰；方便 `mvn -pl pixflow-agent -am test` 做局部验证。
  Date/Author: 2026-06-30 / Codex

- Decision: `SessionMemoryPort` SPI 由 `harness/context` 定义（不放在 `agent`），`agent` 模块实现该 SPI。
  Rationale: SPI 定义方与实现方解耦是 PixFlow 跨模块 SPI 的统一手法（如 `context.TranscriptPort` 由 `session` 实现、`contracts.ConfirmationTokenStore` 由 `infra/cache` 实现、`common.ErrorRecorder` 由 `eval` 实现）；把 `SessionMemoryPort` 放 `context` 让 `context` 模块对 Session Memory 持有中立契约，agent 模块在 Wave 5 装配层实现，符合 `context.md §一.3` SPI 倒置哲学。
  Date/Author: 2026-06-30 / Codex

- Decision: Session Memory 存储模型 = MySQL 单行 + Redis 缓存 + 替换式提取（用户决策 1）。
  Rationale: 单行符合 `session.md §一.3` "append-only 物理存储 + 派生视图"的 PixFlow 持久化哲学；Redis 缓存避免每轮 SELECT；替换式提取避免 diff 合并复杂度，与 `compaction-architecture.md` 的 `SessionMemoryExtractionService` 替换语义一致。落地时**必须回写 `session.md §十七 暂不考虑`** 把"session memory 本期不做"条目移除（已在 Surprises 第 2 条与 Progress 阶段 5 标记）。
  Date/Author: 2026-06-30 / Codex

- Decision: Skill ToolDescriptor 补 `prompt` 字段（用户决策 2），assembler 渲染 `tool_prompt:skill__<name>` 段。
  Rationale: 原 `agent.md §5.1` 把 skill 的 `prompt` 隐式表达为 `description` 字段，导致 description 同时承担 schema 短描述（30 字）与 system prompt 用法片段双重职责；新增 `prompt` 字段让 schema 层的 description 保持极简（≤ 30 字防工具集膨胀），用法片段走 `tool_prompt:skill__<name>` 段（与核心 7 工具的 `tool_prompt:{name}` 段同款机制）。
  Date/Author: 2026-06-30 / Codex

- Decision: 自动记忆召回触发点在 `AgentOrchestrator.streamNewTurn` 入口同步触发（用户决策 3）。
  Rationale: hook 事件 `TURN_STOPPED` / `ASSISTANT_MESSAGE_COMPLETED` 时机在回合结束——若用 hook 触发召回，下一回合才能拿到上一回合的 transcript，存在 1 回合时差；放 AgentOrchestrator 入口能拿到当次 prompt + 当次附件 + 当次会话状态（含 `currentPackageSkuIds`），时序更准。同时 `context.buildForModel` 是纯字符串 in / pure snapshot out（无 RecallSignal 入参），不允许把 IO 塞到渲染热路径——这与 `compaction-architecture.md` "ContextCompactionService 是 preparer 链最内层接入" + `core-runtime-architecture.md` "core 不负责具体 IO" 的口径一致。
  Date/Author: 2026-06-30 / Codex

- Decision: SubagentRunner **必须异步**（用户决策 4），`runAsync(req) → CompletableFuture<SubagentResult>`，child 跑在 `pixflow.agent.subagent.pool` 独立有界线程池（core=4/max=16/queue=100）。
  Rationale: 同步阻塞会让父 loop 长时间卡在 vision LLM 调用上（几秒~十几秒）；异步让父 loop 立即返回继续推进，child 在独立线程池跑。VisionSubagentTool / ExploreSubagentTool 在 handler 内 `.join()` 阻塞 join——但 join 发生在 tools 执行管线的并发池（`pixflow-tools` 配置 `max-concurrency=8`）而非主回合线程，等价于把 child LLM 调用当作"重型工具调用"对待。这是"subagent 必须异步"的工程化落地：在调用线程（tools 池）阻塞等待 child 完成，但 child 本身跑在独立池（subagent 池），两池容量独立可调。
  Date/Author: 2026-06-30 / Codex

- Decision: `SkillToolRegistrar` 走"启动期 `@PostConstruct` 一次性注册"，**本期不做 skill 热加载**（与 `agent.md §5.6` 一致）。
  Rationale: 启动期一次性注册是简单的"全量 + 幂等"模型；热加载要支持运行时 schema 变更 / 工具集重索引 / 跨线程可见性同步，本期无业务需求；TEAM 来源的 skill 上线后引入运行时加载。`registerAll()` 调用发生在 Spring 容器初始化阶段，比任何回合都早，保证 `skill__<name>` 工具在第一回合就已可用。
  Date/Author: 2026-06-30 / Codex

- Decision: 工具 schema 投影统一从 `ToolRegistry.visibleDescriptors(ctx).toolSchemas()` 走，agent 模块**不写 schema 导出器**。
  Rationale: schema 导出归 `harness/tools`（`tool-design-guidelines.md` + `tools.md §五.5` + `tools.md §十三`），agent 只调 `ToolRegistry.toolSchemas(ctx)` 拿 wire format。这与"agent 不持有 provider 具体适配器" + "agent 不持有 ToolDescriptor 内部细节"的双约束一致。`PromptProvider.toolSchemas(state)` SPI 在 agent 层签名是 `List<ToolSchemaView>`（context 的最小 schema 视图，详见 `context.md §十二`），保持 provider-neutral。
  Date/Author: 2026-06-30 / Codex

- Decision: Subagent child AgentLoop **不走 Spring bean**，per-call 构造（与 `loop.md §十二` 一致）。
  Rationale: `AgentLoop` 依赖 15 个 SPI（`RuntimeState` / `MessageStore` / `ContextEngine` / `ChatModelClient` / `ModelRetryRunner` / `ToolExecutor` / ...），且 `RuntimeState` 是 per-conversation 实例，必须每回合重新构造；child 同样 per-subagent-call 构造。SubagentRunner 内部 `ChildRuntimeFactory.build(req)` 在每次 `runAsync` 入口现场 new `AgentLoop`，传入 child RuntimeState（subagent=true, subagentType=vision/explore/...）+ ephemeral MessageStore + 子集 ToolRegistry。
  Date/Author: 2026-06-30 / Codex

- Decision: child MessageStore 用 ephemeral 实例（不绑 `TranscriptPort`），child 中间消息**不落 MySQL message 表**（与 `subagent-architecture.md` "child runtime 独立 MessageStore + 工具裁剪 + 继承父 systemPrompt" 一致）。
  Rationale: subagent 是辅助能力（视觉理解 / 探索），它的中间迭代是 noise 不是 audit 数据；只把 `agent` 工具的最终 `ToolExecutionResult`（含 `finalText` + `child_tool_spans`）回写到父 message 表。这样既避免 child 噪声污染 transcript，也避免 ephemeral store 与 TranscriptPort SPI 冲突（ephemeral 不接 SPI）。
  Date/Author: 2026-06-30 / Codex

- Decision: `PlanModeController` 与 `PlanModeView` **拆两个类**，`PlanModeController` 写 `RuntimeState.metadata`，`RuntimeStatePlanModeView` 读 `RuntimeState.metadata`。
  Rationale: `PlanModeController` 承担状态机迁移（enter/exit + hook 派发 + 草拟计划管理），`PlanModeView` 是 `harness/tools` / `harness/loop` 消费侧的查询 SPI；两者职责不同不合并到一个类。这与 `loop.md §六.2` `RuntimeState` 只承载运行态不解释业务的边界一致——controller 解释 Plan 模式语义，view 仅暴露 boolean。
  Date/Author: 2026-06-30 / Codex

- Decision: 三个 Hook 实现（TurnStoppedSessionMemoryHook / AssistantMemoryIngestionHook / PreCompactSummaryHook）的 order 分别设 100 / 200 / 50，确保 PreCompact 先于任何 cleanup 类 hook 派发、TurnStopped 与 AssistantMessageCompleted 在业务 hook 链中靠后（让审计 / 状态写类 hook 先跑）。
  Rationale: `hooks.md §四.4` 明确 "同事件内确定性排序，小者先执行"；order 是编译期常量，本期一次钉死；未来需要调整时只改常量不调架构。
  Date/Author: 2026-06-30 / Codex

- Decision: `AgentOrchestrator` **不是 Spring 单例**，per-request 由 `module/conversation.TurnDispatchService` 在回合入口 `new` 出来，注入所有依赖。
  Rationale: 与 `loop.md` 落地决策 `LoopAutoConfiguration` 不暴露 `AgentLoop` bean 同款——`AgentOrchestrator` 依赖 `RuntimeState`（per-conversation 实例）+ `MemoryRecallPlanner`（注入但每次重新构造以传入新的 `state`）等"per-turn"对象，**不适合 Spring 单例**。但 `AgentOrchestrator` 内部持有的所有可复用依赖（`MemoryRecallPlanner` / `SubagentRunner` / `SkillLoader` 等）**仍是 Spring 单例**，只是 `AgentOrchestrator` 本身是 per-request 的薄装配层。
  Date/Author: 2026-06-30 / Codex

- Decision: ArchUnit 守护**6 条**断言（含 SubagentRunner 只暴露 runAsync / DynamicPromptAssembler 不在 assemble 内调 IO 等）。
  Rationale: agent 模块是 Wave 5 顶层节点，反向约束必须严；6 条断言覆盖"不依赖 module/* 业务模块" + "不依赖 provider 具体适配器" + "不引用 tools 内部" + "不持有 PO" + "SubagentRunner 强制异步" + "Assembler 强制纯渲染"，与 `dag.md` / `loop.md` 反向约束同款做法。
  Date/Author: 2026-06-30 / Codex

- Decision: Session Memory 断路器（连续失败 ≥ 3 次切换 fallback）由 `SessionMemoryService` 进程内 `AtomicInteger` 自维护，**不引入 Resilience4j 通用断路器**。
  Rationale: Session Memory 断路器语义简单（连续 N 次失败后切 fallback，下一次成功重置），与 Resilience4j CircuitBreaker 的"半开 / 探测 / 全开"三态机不完全对齐；进程内 `AtomicInteger` + `compareAndSet` 足够清晰；引入 Resilience4j 会带来配置项膨胀（slidingWindowSize / failureRateThreshold / waitDurationInOpenState）。失败计数在 `scheduleExtraction` 路径上累计。
  Date/Author: 2026-06-30 / Codex

- Decision: Session Memory child 工具集 = 空（与 `agent.md §8.4` 一致）；vision / explore child 工具集保留 7 核心（除 submit_*/plan）+ skill 工具；不发明"subagent 专属"工具空间。
  Rationale: 与 `tools.md §十八` "固定 7 个 Agent 级动作" + `agent.md §8.4` "child 运行时工具集裁剪" 一致；skill 工具对 vision/explore 有价值（视觉理解可能需要"美妆水印规范"等知识），对 summarization/提取无价值（摘要是读 + 总结，不需要查外部数据）。
  Date/Author: 2026-06-30 / Codex

- Decision: `MessageStore.appendSkillInvocation` / `appendPlanModeChange` 两个新方法由 `pixflow-context` 的 `DefaultMessageStore` 实现类提供（不是 SPI 接口），不修改 `MessageStore` 接口本身。
  Rationale: 这两个方法是"agent 模块需要的事件 trail"，不是核心消息类型；放在 `DefaultMessageStore` 实现类（SPI consumer 实现）而非 SPI 接口，保持 SPI 稳定。调用方约定 `role=USER`（事件 trail 是 agent 行为而非用户/工具产生）。
  Date/Author: 2026-06-30 / Codex

- Decision: **本期不引入 background agent**（跨回合 / 跨进程 / 队列化子 Agent），与 `loop.md §十八` 一致。
  Rationale: 异步 SubagentRunner 已满足"subagent 不阻塞父 loop"的本期需求；background agent 涉及 RabbitMQ 队列 + worker 进程 + resume 协议，复杂度远超本期需要。`loop.md §十八` 明确"loop 不做后台任务管理"，agent 配合该决策。
  Date/Author: 2026-06-30 / Codex

- Decision: **不实现 fork 模式** SubagentRequest（`subagent_type is None` 触发 fork 父历史），与 `agent.md §八.3` 一致。
  Rationale: fork 模式涉及父消息链深拷贝 + 占位 tool_result 补全 + 继承父 systemPrompt 字节，复杂度高；clean 模式（本期唯一支持）满足 vision / explore / summarization / session memory 提取全部场景；未来若需要"基于历史上下文做决策"的子 Agent，再单独评估 fork 模式。
  Date/Author: 2026-06-30 / Codex

- Decision: Skill 的 `BUILTIN` 来源本期是 classpath 资源（`skills/<name>/SKILL.md`），启动期 `SkillLoader` 一次性同步入 MySQL；`PROJECT` / `TEAM` 来源本期 enum 保留但**不启用**（与 `agent.md §5.4` 一致）。
  Rationale: 单一来源（BUILTIN）让 SkillLoader 实现极简（全量扫 + 幂等同步）；PROJECT 是未来多项目配置的扩展点；TEAM 涉及 MinIO + metadata 入表 + 异步抽取，是另一个完整的子模块（与本期范围解耦）。enum 字段保留扩展能力，本期不实现。
  Date/Author: 2026-06-30 / Codex

## Context and Orientation

### 当前状态

仓库根目录 `D:\study\PixFlow` 已按 Maven multi-module 组织，26 个模块已落地（`pixflow-common` / `pixflow-contracts` / `pixflow-infra-ai` / `pixflow-infra-storage` / `pixflow-infra-cache` / `pixflow-harness-loop` / `pixflow-harness-tools` / `pixflow-harness-context` / `pixflow-harness-session` / `pixflow-harness-hooks` / `pixflow-harness-eval` / `pixflow-harness-permission` / `pixflow-harness-state` / `pixflow-module-memory` / `pixflow-module-file` / `pixflow-module-dag` 等），`module-dependency-dag-plan.md` Wave 5 把 `agent` 模块标记为未完成。`docs/design-docs/agent/agent.md`（1769 行）2026-06-30 落地，作为本计划的权威设计文档。`harness-loop` 与 `harness-tools` 与 `harness-context` 与 `harness-session` 与 `harness-hooks` 与 `harness-eval` 与 `harness-permission` 全部落地，SPI 接缝已知；`module/memory` 的 `MemoryService` / `MemoryContextRequest` / `MemoryContext` 已知，agent 模块直接消费。

`pixflow-agent` Maven 模块不存在（`pom.xml:19-46` 的 `<modules>` 块不含 `pixflow-agent`），根包 `com.pixflow.agent` 0 个 Java 文件。本计划创建该模块并落地 17 个核心子包（`prompt/` / `prompt/sections/` / `skill/` / `memory/` / `sessionmemory/` / `subagent/` / `subagent/tools/` / `summarization/` / `planmode/` / `hooks/` / `config/` / `error/` / `architecture/` 等）+ 1 个 SQL 迁移目录 + 1 个 skill 资源目录。

### 关键术语

下列术语在本计划中频繁使用；如已在 `agent.md` 定义则引用其段落号。

- **Section（Prompt section）**：system prompt 的一个段落，由 `PromptSection.render()` 输出 Markdown 文本。`agent.md §4.1` 表 12+ section 顺序与 fingerprint 规则。
- **Fingerprint**：section 的缓存键，由 section 类型 + 上游输入哈希组成，fingerprint 变则缓存失效重渲染。
- **PromptSectionCache**：进程内 section 级缓存，本期用 Caffeine 实现（LRU + stats），不引 Redis（`agent.md §4.3`）。
- **`skill__<name>`**：skill 工具的命名空间化名称，描述符由 `SkillToolRegistrar.@PostConstruct` 注入 `DefaultToolRegistry`（`agent.md §5.2`）。
- **Session Memory**：单会话级累积 Markdown 视图，MySQL 单行 + Redis 缓存，替换式提取，断路器 3 次后 fallback 规则式（`agent.md §7` + 用户决策 1）。
- **`SessionMemoryPort` SPI**：定义在 `harness/context`（阶段 1 新增），由 `agent` 模块 `SessionMemoryService` 实现（`agent.md §7.7`）。
- **`last_summarized_seq`**：Session Memory 事实状态，**唯一**持久化的 Session Memory 字段；阈值状态（"当前累积多少 token"）不持久化，重入会话时重算（`agent.md §7.3.1`）。
- **RRF（Reciprocal Rank Fusion）**：多通道排名融合算法，k=60 可配（`agent.md §6.3`）。
- **`MemoryRecallPlanner`**：agent 层每轮 `buildForModel` 前同步触发 4 通道召回 + RRF 融合 + token 预算裁剪（`agent.md §6`）。
- **SubagentRunner**：单一类，三处复用（`agent` 工具 / `SummarizationPort` / `SessionMemoryExtractor`），**异步**返回 `CompletableFuture<SubagentResult>`（`agent.md §8` + 用户决策 4）。
- **Child Runtime**：subagent 跑的独立 RuntimeState + ephemeral MessageStore（不绑 TranscriptPort）+ 子集 ToolRegistry + child ContextEngine + child AgentLoop；`subagent_type` 决定工具集裁剪（`agent.md §8.4`）。
- **Plan Mode**：会话级只读模式，状态写在 `RuntimeState.metadata["planMode"]`；`plan` / `plan_exit` 工具 handler 由 agent 实现，效果靠 PlanModeView (tools 可见集) + Permission (硬 deny) + prompt (引导) 三层强制（`agent.md §11`）。
- **HookCallback 三个实现**：`TurnStoppedSessionMemoryHook`（订阅 `TURN_STOPPED`，触发 Session Memory 异步提取）/ `AssistantMemoryIngestionHook`（订阅 `ASSISTANT_MESSAGE_COMPLETED`，触发分析结论记忆异步巩固）/ `PreCompactSummaryHook`（订阅 `PRE_COMPACT`，注入 `compact.summaryInstructions` metadata）（`agent.md §13` hook 行）。
- **AgentOrchestrator**：per-request 入口薄装配层，由 `module/conversation.TurnDispatchService` 在回合入口 new 出来（非 Spring 单例）；驱动 `agentLoop.stream(...)` 完成回合（`agent.md §12`）。

### 模块结构

源码包 `com.pixflow.agent`（与仓库根包 `com.pixflow` 对齐）。Maven 模块 `pixflow-agent`（需加入根 `pom.xml` `<modules>` + `<dependencyManagement>`）。

```
pixflow-agent/
├── AgentOrchestrator.java              # 入口：装配 loop + 所有依赖，驱动 stream()
├── prompt/
│   ├── DynamicPromptAssembler.java     # 渲染 + section 缓存
│   ├── PromptSection.java              # record (key/title/body/fingerprint/cacheable)
│   ├── PromptRuntimeContext.java       # 装载 state + visibleTools + memoryRecall + sessionMemory + activeSkills
│   ├── PromptSectionCache.java         # SPI：Caffeine 实现
│   ├── CaffeinePromptSectionCache.java # 进程内 LRU 实现
│   ├── PromptSummary.java              # 给 eval / trace 用的摘要视图
│   ├── PromptProvider.java             # SPI（loop 调）
│   └── sections/
│       ├── IdentitySection.java        # 固定
│       ├── BehaviorRulesSection.java   # 固定
│       ├── RiskAndSafetySection.java   # 固定（含 HITL 强规则）
│       ├── VerificationSection.java    # 固定
│       ├── PreferenceSection.java      # 用户偏好画像
│       ├── SessionMemorySection.java   # Session Memory 累积要点
│       ├── LongTermMemorySection.java  # SKU 历史 + 分析结论（RRF 融合）
│       ├── ActiveSkillsSection.java    # 已加载 skill 列表
│       ├── WorkspaceStateSection.java  # 当前素材包 / 会话上下文 / Plan 模式
│       ├── ToolPromptSection.java      # 工具 prompt 汇总（与 schema 同源）
│       └── ToolPromptForNameSection.java # 单工具 prompt 段
├── skill/
│   ├── SkillRepository.java            # MyBatis-Plus mapper
│   ├── Skill.java                      # entity (@TableName("skill"))
│   ├── SkillRow.java                   # 与 Skill 同形 record（避免 PO 泄漏）
│   ├── SkillLoader.java                # 启动期从 skills/<name>/SKILL.md 同步入表
│   ├── SkillToolRegistrar.java         # 把 skill 动态注册成 skill__<name> tool descriptor
│   ├── SkillHandler.java               # 实现 ToolHandler，handler 自身不调 LLM
│   ├── SkillFrontmatter.java           # SKILL.md frontmatter record
│   └── SkillFrontmatterParser.java     # 简单 key-value 解析（不引 SnakeYAML）
├── memory/
│   ├── MemoryRecallPlanner.java        # 召回规划：信号→通道→结果
│   ├── MemoryRecallResult.java         # RRF 融合产物
│   ├── MemoryRecallSignal.java         # 信号 A-E
│   ├── MemorySection.java              # 单 section 视图
│   ├── SkuMentionExtractor.java        # 从 assistant message 抽取 SKU 列表
│   ├── RrfMerger.java                  # 多通道 RRF 融合 + token 上限裁剪
│   └── RecallChannel.java              # enum: PREFERENCE / SKU_HISTORY / INSIGHT_VECTOR / INSIGHT_FULLTEXT
├── sessionmemory/
│   ├── SessionMemoryService.java       # 实现 context.SessionMemoryPort
│   ├── SessionMemoryRepository.java    # MyBatis-Plus mapper
│   ├── SessionMemoryCache.java         # Redis 缓存
│   ├── SessionMemoryExtractor.java     # fork child LLM 提取当回合要点
│   ├── SessionMemoryUpdater.java       # fallback 规则式提取
│   ├── SessionMemoryThreshold.java     # 运行时阈值状态（不持久化，纯计算）
│   ├── SessionMemoryContent.java       # Markdown 结构
│   └── SessionMemoryRow.java           # 与 SessionMemoryContent 同形 record
├── subagent/
│   ├── SubagentRunner.java             # 装配 child runtime + 调 runAsync（异步）
│   ├── SubagentRequest.java            # 含 type/prompt/parentToolCallId/mode
│   ├── SubagentResult.java             # 含 finalText/usage/error/transition
│   ├── ChildRuntimeFactory.java        # 构造 child RuntimeState + MessageStore + ToolRegistry + ContextEngine
│   ├── ChildToolFilter.java            # 工具集裁剪规则
│   ├── SubagentPool.java               # 独立线程池封装
│   └── tools/
│       ├── VisionSubagentTool.java     # 实现 agent(type=vision) handler
│       └── ExploreSubagentTool.java    # 实现 agent(type=explore) handler
├── summarization/
│   ├── ForkChildSummarizationPort.java # 实现 context.SummarizationPort
│   ├── SummaryPromptBuilder.java       # 构造 SummarizationRequest → subagent prompt
│   └── PreCompactSummaryHook.java      # PRE_COMPACT → 注入 compact.summaryInstructions
├── planmode/
│   ├── PlanModeState.java              # enum: OFF / ACTIVE
│   ├── PlanModeController.java         # enter/exit 状态机
│   ├── PlanModePrompter.java           # 把 lastPlanDraft 拼到下回合 WorkspaceStateSection
│   ├── RuntimeStatePlanModeView.java   # 实现 PlanModeView 读 RuntimeState.metadata
│   ├── AgentPlanToolHandler.java       # plan 工具 handler
│   └── AgentPlanExitToolHandler.java   # plan_exit 工具 handler
├── hooks/
│   ├── TurnStoppedSessionMemoryHook.java    # TURN_STOPPED → 触发 Session Memory 异步累积
│   ├── AssistantMemoryIngestionHook.java   # ASSISTANT_MESSAGE_COMPLETED → 触发分析结论抽取
│   └── PreCompactSummaryHook.java          # PRE_COMPACT → 注入 summaryInstructions
├── config/
│   ├── AgentProperties.java            # @ConfigurationProperties(prefix="pixflow.agent")
│   └── AgentAutoConfiguration.java     # Spring bean 装配
├── error/
│   └── AgentErrorCode.java             # enum implements ErrorCode
└── architecture/
    └── AgentArchitectureTest.java      # ArchUnit 守护（6 条）
```

依赖方向（执行期严格遵守，ArchUnit 守护）：

```
agent ──► harness/{loop, tools, context, hooks, session, eval, permission}
agent ──► module/{memory, file}
agent ──► infra/{ai, storage, cache}
agent ──► common + contracts
```

**关键倒置**：

- `agent` 实现 `context.SummarizationPort`（destructive compaction 摘要 SPI）
- `agent` 实现 `context.SessionMemoryPort`（Session Memory 累积 SPI）
- `agent` 实现 `hooks.HookCallback`（3 个业务 hook 接线点）
- `agent` 实现 `tools.ToolHandler`（`agent` 工具 + `plan` / `plan_exit` 工具）

**新增依赖边**（需同步 `module-dependency-dag-plan.md`）：

- `context → agent`（agent 实现 SPI 倒置；agent → context 是编译期反向，但 SPI 倒置边表示依赖）
- `hooks → agent`（agent 提供 hook bean）
- `session → agent`（agent 不写但允许读 message 表，按 agent.md 边界）

**反向硬约束**（ArchUnit 断言）：

- `com.pixflow.agent..` **不依赖** `module/dag` / `module/task` / `module/commerce` / `module/vision` / `module/imagegen` / `module/rubrics` / `module/conversation`
- `com.pixflow.agent..` **不依赖** provider 具体适配器（`OpenAiChatModelClient` / `DashScopeChatModelClient` / `AliyunChatModelClient` 等）
- `com.pixflow.agent..` **不引用** `harness/tools` 的内部类型（除 5 个公开 SPI 外）
- `com.pixflow.agent..` **不持有** `MessageEntity` / `CompactionEntity` / `SessionEntity` / `SkillRow` 等 PO
- `SubagentRunner` 强制 `runAsync()` 返回 `CompletableFuture`，**不暴露**同步 `.run()`
- `DynamicPromptAssembler.assemble` **不调** `ChatModelClient.call(...)` / `MemoryService.prepareContext(...)` 等 IO 操作

## Plan of Work

本计划分 10 个里程碑，按依赖顺序严格串联。每个里程碑收尾跑 `mvn -pl pixflow-agent -am compile` 或 `mvn -pl pixflow-agent -am test`（含测试的里程碑）全绿才进入下一里程碑。

### 里程碑 1：Maven 模块骨架 + SPI 补位

**目标**：在 `pixflow-agent` 模块下创建 Maven 骨架，**先在 `pixflow-context` 补 `SessionMemoryPort` SPI**（agent 阶段 5 实现该 SPI 的前置）。

**新增文件**：

- 根 `pom.xml` 的 `<modules>` 加 `<module>pixflow-agent</module>`，`<dependencyManagement>` 加 `pixflow-agent` 依赖管理条目（不写 version）。
- `pixflow-agent/pom.xml`：依赖 `pixflow-common` / `pixflow-contracts` / `pixflow-harness-loop` / `pixflow-harness-tools` / `pixflow-harness-context` / `pixflow-harness-session` / `pixflow-harness-hooks` / `pixflow-harness-eval` / `pixflow-harness-permission` / `pixflow-module-memory` / `pixflow-module-file` / `pixflow-infra-ai` / `pixflow-infra-storage` / `pixflow-infra-cache` / `mybatis-plus-spring-boot3-starter` 3.5.7 / `redisson-spring-boot-starter` / `caffeine` / `spring-boot-autoconfigure` / `spring-boot-configuration-processor`（optional）/ `spring-boot-starter-test`（test）/ `archunit-junit5`（test）/ `testcontainers`（test）。
- `pixflow-agent/src/main/java/com/pixflow/agent/` + 11 个子包目录。
- `pixflow-context/src/main/java/com/pixflow/harness/context/sessionmemory/SessionMemoryPort.java`（4 方法 + 2 record）。
- `docs/design-docs/harness/context.md` §三 / §四 / §十四 同步 `SessionMemoryPort` SPI 定义说明。
- `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 补 `context --> agent` 边。

**关键实现细节**：

- `SessionMemoryPort.load(conversationId) → Optional<SessionMemoryContent>`：Redis miss → MySQL fallback；返回 `SessionMemoryContent(markdown, contentHash)`。
- `SessionMemoryPort.save(conversationId, content, lastSummarizedSeq)`：单事务 `INSERT ... ON DUPLICATE KEY UPDATE`，更新 `updated_at` 与 `content_hash`。
- `SessionMemoryPort.computeThreshold(conversationId, currentHeadSeq, currentTurnNo) → SessionMemoryThreshold`：从 load 拿事实状态 + 增量 messages 重算。
- `SessionMemoryPort.scheduleExtraction(conversationId, turnNo)`：提交异步任务，立即返回 noop。

**测试要点**：

- `mvn -pl pixflow-context -am test` 全绿（已有 110+ 测试不破）。
- `mvn -pl pixflow-agent -am compile` 编译通过（无源码只有空包，但模块能解析）。

**验证**：`mvn -pl pixflow-context -am test` BUILD SUCCESS，agent 模块目录存在但内容空。

### 里程碑 2：装配层核心抽象与 section 渲染

**目标**：实现 `DynamicPromptAssembler` + `PromptSectionCache` + 11 个 section 实现 + `PromptRuntimeContext`，纯函数零 Spring 上下文，单元测试 60+。

**新增文件**：

- `prompt/PromptSection.java`（record 6 字段 + render()）。
- `prompt/PromptRuntimeContext.java`（record 11 字段）。
- `prompt/PromptSectionCache.java`（interface）+ `prompt/CaffeinePromptSectionCache.java`（Caffeine LRU + stats）。
- `prompt/PromptSummary.java`（只读视图）。
- `prompt/PromptProvider.java`（SPI）。
- `prompt/DynamicPromptAssembler.java`（核心：assemble 流程 + 缓存查写）。
- `prompt/sections/IdentitySection.java` 等 11 个 section 类。
- `src/main/resources/prompts/identity.md` / `behavior_rules.md` / `risk_and_safety.md` / `verification_and_reporting.md`（4 个固定 section 的资源文件）。

**关键实现细节**：

- `PromptSection.render()` 返回 `"# {title}\n{body}"`（与 `prompt-architecture.md` 一致）。
- `DynamicPromptAssembler.assemble(ctx)`：按 `agent.md §4.1` 表 12+ 顺序串行 `section.render()`，空 body 过滤，`"\n\n".join`。
- 缓存查写：`cache.get(key, fingerprint)` 命中复用；未命中调 `section.render()` 后 `cache.put(key, fingerprint, rendered)`。
- fingerprint 计算：固定 section 用 `sectionVersion`；动态 section 用 sha256 / md5 / "{a}.{b}" 等组合（详见 `agent.md §4.2`）。
- `ToolPromptForNameSection(key="tool_prompt:<name>")` 与 `ToolPromptSection(key="available_tools")` 是两个独立 section，**不是同一个**——`available_tools` 段聚合所有可见 tool 的 prompt 字段；`tool_prompt:<name>` 段单独渲染某个 tool 的 prompt 字段。

**测试要点**：

- `PromptSectionTest`：render / fingerprint 单调 / 重排稳定。
- `CaffeinePromptSectionCacheTest`：LRU 淘汰、stats 计数、并发安全、invalidateAll。
- `DynamicPromptAssemblerTest`：固定顺序、空 body 过滤、cache 命中短路、各 section fingerprint 独立生效。
- `PromptRuntimeContextTest`：record 字段映射。

**验证**：`mvn -pl pixflow-agent -am test` 至少 60 个测试方法全绿。

### 里程碑 3：Skill 机制（工具化 + 渐进披露 + 同步入表）

**目标**：实现 `SkillLoader` + `SkillToolRegistrar` + `SkillHandler` + 资源 SKILL.md，启动期一次性同步入 MySQL，工具按需调 `skill__<name>` 拿 body。

**新增文件**：

- `src/main/resources/skills/beauty_watermark/SKILL.md`（示例 frontmatter + body）。
- `skill/SkillFrontmatter.java` + `skill/SkillFrontmatterParser.java`。
- `skill/Skill.java`（entity）+ `skill/SkillRow.java`（record）。
- `skill/SkillRepository.java`（MyBatis-Plus mapper）。
- `skill/SkillLoader.java`（`@PostConstruct syncBuiltIn`）。
- `skill/SkillToolRegistrar.java`（`@PostConstruct registerAll`，**先合入 pixflow-tools 扩展**）。
- `skill/SkillHandler.java`（实现 `ToolHandler`）。
- `src/main/resources/db/migration/V1__create_skill.sql`（11 字段 + 索引）。

**关键实现细节**：

- `SkillFrontmatterParser.parse(Path)`：简单 `---` 之间 key-value 解析，不引 SnakeYAML（避免依赖膨胀）；支持 4 字段 + body 段。
- 命名校验 `validate(name, ...)`：name 正则 `^[a-z][a-z0-9_]{1,40}$`、description ≤ 200、when_to_use ≤ 500、version 是正整数、body 字节 ≤ 32KB。
- `SkillLoader.syncBuiltIn()`：扫 `classpath:skills/*/SKILL.md` → parse → SELECT by name → INSERT/UPDATE/skip；删除启动时未出现的 BUILTIN 行；格式校验失败记 WARN + 指标，不抛异常。
- `SkillToolRegistrar.registerAll()`：查 `skillRepository.findAllBySource(BUILTIN)` → 构造 `ToolDescriptor(name="skill__<name>", ..., prompt=skill.description+"\n调用时机："+skill.whenToUse, readOnlyHint=true, handler=SkillHandler, classifier=FailClosedReadOnlyClassifier, validator=NoOpValidator)` → 调 `toolRegistry.registerDynamic(descriptor)`。
- `SkillHandler.handle(inv)`：剥 `skill__` 前缀 → `skillRepository.findByName(name)` → 不存在抛 `SkillNotFoundException` → 返回 `ToolHandlerOutput(body, metadata{skill_name, skill_version, body_chars})`；body > 50KB 走 `toolResultStorage.write(...)` 外置。

**测试要点**：

- `SkillFrontmatterParserTest`：合法 4 字段 + 各种缺/越界。
- `SkillLoaderTest`：幂等同步、UPDATE 路径、DELETE 路径、校验失败不抛。
- `SkillHandlerTest`：handler 返回 body + 缺失抛 `SkillNotFoundException` + body > 50KB 走外置。
- `SkillToolRegistrarTest`：注册 N 个 descriptor、name 重复抛 `IllegalStateException`。

**验证**：`mvn -pl pixflow-agent -am test` 至少 30 个测试方法新增全绿。

### 里程碑 4：自动记忆召回

**目标**：实现 `MemoryRecallPlanner` + `RrfMerger` + `SkuMentionExtractor` + 4 通道服务调用 + token 预算裁剪，单元测试 40+。

**新增文件**：

- `memory/RecallChannel.java`（enum 4 值）。
- `memory/MemoryItem.java` + `memory/MemorySection.java` + `memory/MemoryRecallSignal.java` + `memory/MemoryRecallResult.java`（4 record）。
- `memory/SkuMentionExtractor.java`（两阶段 regex + LLM 兜底 + Redis 缓存）。
- `memory/RrfMerger.java`（标准 RRF k=60 + jtokkit token 预算裁剪）。
- `memory/MemoryRecallPlanner.java`（并发触发 4 通道 + 降级矩阵）。

**关键实现细节**：

- `SkuMentionExtractor.extract(messages)`：regex 阶段匹配配置 `pixflow.agent.memory.sku-patterns`（默认 `^SKU\d+$` / `^[A-Z]{2,}\d{4,}$`），命中 ≥ 3 直返；LLM 阶段调 `ChatModelClient.call(...)` 抽取 + Redis 缓存键 `pixflow:agent:sku_extract:{messageHash}` TTL 24h。
- `RrfMerger.merge(channels, k=60, maxTokens=4000)`：标准 RRF 融合（score = Σ 1/(k + rank_i)） + 累加 token 直至 ≤ maxTokens 截断 + 返回 `MemoryRecallResult.totalTokens`。
- `MemoryRecallPlanner.plan(signal)`：4 通道并行（`CompletableFuture` + 公共 ForkJoinPool），任一通道失败记 warn 并降级（向量失败只保留 FULLTEXT / FULLTEXT 失败只保留向量）；最后 `RrfMerger.merge(...)`。
- **类目 filter 留接口不实现**：本期 Qdrant filter 始终为空。

**测试要点**：

- `SkuMentionExtractorTest`：regex 命中 ≥ 3 直返、LLM 兜底、Redis 缓存命中。
- `RrfMergerTest`：属性测试 4 通道融合单调性 + token 截断边界 + 单通道为空退化。
- `MemoryRecallPlannerTest`：mock 4 通道服务，验证并发触发 + 降级矩阵 + recallPlanId 是新 UUID + totalTokens 等于实际累计。

**验证**：`mvn -pl pixflow-agent -am test` 至少 25 个测试方法新增全绿。

### 里程碑 5：Session Memory 累积提取

**目标**：实现 `SessionMemoryService`（实现 `SessionMemoryPort`）+ `SessionMemoryExtractor` + `SessionMemoryUpdater` + 断路器 + Redis 缓存，单元测试 30+。

**新增文件**：

- `sessionmemory/SessionMemoryContent.java` + `SessionMemoryThreshold.java` + `SessionMemoryRow.java`（3 record）。
- `sessionmemory/SessionMemory.java`（entity）。
- `sessionmemory/SessionMemoryRepository.java`（MyBatis-Plus mapper）。
- `sessionmemory/SessionMemoryCache.java`（Redis 缓存封装）。
- `sessionmemory/SessionMemoryService.java`（实现 `SessionMemoryPort` 4 方法 + 断路器）。
- `sessionmemory/SessionMemoryExtractor.java`（fork child LLM 提取）。
- `sessionmemory/SessionMemoryUpdater.java`（fallback 规则式提取）。
- `src/main/resources/db/migration/V2__create_session_memory.sql`（7 字段 + 索引）。

**关键实现细节**：

- `SessionMemoryService` 4 方法按 SPI 契约实现（详见里程碑 1）。
- 断路器：进程内 `AtomicInteger consecutiveFailures`，连续失败 ≥ `pixflow.agent.session-memory.circuit-breaker.max-consecutive-failures`（默认 3）后切 fallback；下一次成功重置。
- `SessionMemoryExtractor.extract()`：从 `context.MessageStore.currentMessages()` 过滤 `seq > lastSummarizedSeq` 拿增量；构造 child prompt（按 `agent.md §7.4.3` Markdown 结构模板）；调 `subagentRunner.runAsync(...)` 跑 child LLM 提取；child 工具集为空。
- `SessionMemoryUpdater.extractFallback()`：拼接 "## (Fallback) 最近对话摘要" 段，含最近 1 条 user message 全文 + 最后 1 条 assistant message 前 500 字。
- 异步提交 `scheduleExtraction(conversationId, turnNo)`：提交 `SessionMemoryExtractionTask` 到独立线程池（`pixflow.agent.session-memory.extraction-pool`，core=2/max=4/queue=100），立即返回 noop。

**测试要点**：

- `SessionMemoryServiceTest`：MyBatis-Plus 替身 + Redis mock 验证缓存 miss/hit 双路径、save 顺序、阈值重算场景"上次 seq=50 当前 head=120" 增量 = 70 条、断路器计数。
- `SessionMemoryExtractorTest`：mock SubagentRunner 返回新 content → save 调一次。
- `SessionMemoryUpdaterTest`：fallback content 拼接格式正确、不调 LLM。
- `SessionMemoryThresholdTest`：shouldExtract 在 token ≥ 10K 或 turn ≥ 3 时为真。

**文档回写**：`docs/design-docs/harness/session.md` §十七 删除"session memory（跨压缩连续性 Markdown + 提取子 Agent）本期不做"条目，加 revision notes 段。

**验证**：`mvn -pl pixflow-agent -am test` 至少 25 个测试方法新增全绿。

### 里程碑 6：异步 Subagent Runner + 三处复用

**目标**：实现 `SubagentRunner` 异步版本 + `ChildRuntimeFactory` + `ChildToolFilter` + `VisionSubagentTool` + `ExploreSubagentTool` + `ForkChildSummarizationPort`，单元测试 30+。

**新增文件**：

- `subagent/SubagentRequest.java` + `subagent/SubagentResult.java`（2 record）。
- `subagent/SubagentType.java`（enum 4 值：VISION / EXPLORE / SUMMARIZATION / SESSION_MEMORY_EXTRACTION）。
- `subagent/ChildRuntimeFactory.java`（构造 child RuntimeState + MessageStore + ToolRegistry + ContextEngine + AgentLoop）。
- `subagent/ChildToolFilter.java`（按 type 决定工具集）。
- `subagent/SubagentPool.java`（独立线程池封装，@Bean 暴露 ExecutorService）。
- `subagent/SubagentRunner.java`（`runAsync(req) → CompletableFuture<SubagentResult>`）。
- `subagent/tools/VisionSubagentTool.java`（实现 `ToolHandler`）。
- `subagent/tools/ExploreSubagentTool.java`（实现 `ToolHandler`）。
- `summarization/ForkChildSummarizationPort.java`（实现 `SummarizationPort`）。
- `summarization/SummaryPromptBuilder.java`（构造 child prompt）。

**关键实现细节**：

- `SubagentRunner.runAsync(req)`：submit 到 `subagentPool`，**不**同步阻塞；返回 `CompletableFuture<SubagentResult>`。
- 主路径在 subagentPool 线程上跑：`ChildRuntimeFactory.build(req)` → child AgentLoop per-call 构造（**非 Spring bean**）→ child ephemeral MessageStore（不绑 TranscriptPort）→ child `continueStream` 跑 `CapturingSink` → 抽 `finalText + usage + toolResultCount` → 包装为 `SubagentResult`。
- 异常路径：child 抛不可恢复异常 → `SubagentResult(isError=true, errorMessage=safeMsg)`，**不冒泡**到调用线程。
- `ChildToolFilter.build(type)`：
  - `VISION` / `EXPLORE`：core 7 工具，禁用 `submit_image_plan` / `submit_imagegen_plan` / `plan`。
  - `SUMMARIZATION` / `SESSION_MEMORY_EXTRACTION`：全部禁用 7 核心工具。
- `VisionSubagentTool.handle(inv)`：解析 `{type, imageIds, prompt}` → `SubagentRequest.vision(...)` → `subagentRunner.runAsync(req).join()` 阻塞 join → 返回 `ToolHandlerOutput(finalText, metadata{subagent_type, usage, tool_result_count, child_tool_spans})`。
- `ExploreSubagentTool.handle(inv)`：同上 type=explore。
- `ForkChildSummarizationPort.summarize(req)`：构造 `SubagentRequest.summary(...)` → `subagentRunner.runAsync(req).join()` → 成功返回 `SummaryResult.ok(finalText)`，失败 `circuitBreaker.recordFailure()` + `SummaryResult.failed(errorMessage)`。

**测试要点**：

- `ChildToolFilterTest`：4 type 各自的禁用列表精确。
- `SubagentRunnerTest`：mock child AgentLoop 的 continueStream，验证异步提交 + 结果捕获 + 异常降级。
- `VisionSubagentToolTest`：mock SubagentRunner，验证参数解析 + child_spans metadata 透传。
- `ForkChildSummarizationPortTest`：mock SubagentRunner，验证成功返回 / 失败上报断路器。

**验证**：`mvn -pl pixflow-agent -am test` 至少 25 个测试方法新增全绿。

### 里程碑 7：Plan 模式控制器 + Agent Orchestrator 入口 + Hook 接线

**目标**：实现 `PlanModeController` + `RuntimeStatePlanModeView` + `AgentPlanToolHandler` + `AgentPlanExitToolHandler` + `AgentOrchestrator` + 3 个 `HookCallback` 实现，单元测试 20+。

**新增文件**：

- `planmode/PlanModeState.java`（enum 2 值）。
- `planmode/PlanModeController.java`（enter/exit 状态机）。
- `planmode/PlanModePrompter.java`（把 lastPlanDraft 拼到下回合 WorkspaceStateSection）。
- `planmode/RuntimeStatePlanModeView.java`（实现 `PlanModeView`）。
- `planmode/AgentPlanToolHandler.java`（plan 工具 handler）。
- `planmode/AgentPlanExitToolHandler.java`（plan_exit 工具 handler）。
- `AgentOrchestrator.java`（per-request 入口）。
- `hooks/TurnStoppedSessionMemoryHook.java`（订阅 TURN_STOPPED, order=100）。
- `hooks/AssistantMemoryIngestionHook.java`（订阅 ASSISTANT_MESSAGE_COMPLETED, order=200）。
- `hooks/PreCompactSummaryHook.java`（订阅 PRE_COMPACT, order=50）。
- `config/AgentProperties.java`（`@ConfigurationProperties(prefix="pixflow.agent")`）。
- `config/AgentAutoConfiguration.java`（Spring bean 装配）。

**关键实现细节**：

- `PlanModeController.enter()`：校验当前 OFF → `state.putMetadata("planMode", true)` → 派发（未来）`PLAN_MODE_ENTERED` 事件（本期不派发，理由见 Surprises 第 7 条）。
- `PlanModeController.exit(draftPlan)`：校验当前 ACTIVE → `state.putMetadata("planMode", false)` + `state.putMetadata("lastPlanDraft", draftPlan)`。
- `RuntimeStatePlanModeView.isPlanMode()`：读 `state.metadataOrDefault("planMode", Boolean.FALSE)`。
- `AgentPlanToolHandler.handle(inv)`：调 `planModeController.enter()` → 返回 `ToolHandlerOutput("Entered Plan mode. Read-only tools only; write tools hidden.")`。
- `AgentPlanExitToolHandler.handle(inv)`：解析 `draftPlan` 入参 → 调 `planModeController.exit(draftPlan)` → 返回 `ToolHandlerOutput("Exited Plan mode. Plan draft saved.", metadata{draftPlan})`。
- `AgentOrchestrator.streamNewTurn(conversationId, prompt, attachments, sink)`：8 步编排（见 Progress 阶段 7）。
- `AgentOrchestrator.continueTurn(conversationId, sink)`：跳过 user prompt 派发与追加，剩余同 streamNewTurn。
- 3 个 `HookCallback` 实现：每个 `handle(event, payload)` 立即返回 `HookResult.noop()`，**内部提交异步任务**（session memory 提取 / 记忆巩固）或返回 `HookResult.withMetadata(...)`（compact.summaryInstructions）。
- `AgentProperties` 14 子段（prompt.section-cache / skill / memory.recall / session-memory / subagent.pool / plan-mode / orchestrator）。
- `AgentAutoConfiguration` 暴露所有 `@Bean` + `@EnableConfigurationProperties(AgentProperties.class)` + `@MapperScan` + subagent pool 的 `ExecutorService` bean（`@Bean(destroyMethod="shutdown")`）。

**测试要点**：

- `PlanModeControllerTest`：enter 校验 + 重复 enter 拒绝 + exit 校验 + lastPlanDraft 写入。
- `AgentPlanToolHandlerTest`：handler 返回 Entered 文案 + metadata 正确。
- `AgentPlanExitToolHandlerTest`：handler 返回 Exited 文案 + draftPlan metadata 正确。
- `AgentOrchestratorTest`：mock 所有依赖，验证 8 步编排顺序 + 同步召回触发点 + PromptProvider 注入。
- `TurnStoppedSessionMemoryHookTest`：payload.runtime().subagent()==true 时返回 noop（child 不触发）。
- `PreCompactSummaryHookTest`：返回 `compact.summaryInstructions` metadata key。

**验证**：`mvn -pl pixflow-agent -am test` 至少 20 个测试方法新增全绿。

### 里程碑 8：ArchUnit 守护 + 跨模块扩展

**目标**：把 `DefaultToolRegistry` 扩展为支持运行时注册 + `DefaultMessageStore` 新增 2 方法 + ArchUnit 6 条断言全绿。

**跨模块修改**：

- `pixflow-tools/src/main/java/com/pixflow/harness/tools/DefaultToolRegistry.java`：
  - 字段 `descriptors` 从 immutable List 改为 `ConcurrentHashMap<String, ToolDescriptor>`。
  - 新增 `registerDynamic(ToolDescriptor)` 方法（线程安全）。
  - 新增 `unregisterDynamic(String)` 方法（未来用，本期不调用）。
  - 保持旧构造签名兼容（`@Autowired List<ToolDescriptor>` 仍工作）。
  - `visibleDescriptors(ctx)` 改为 `ConcurrentHashMap.values()` 流过滤。
- `pixflow-context/src/main/java/com/pixflow/harness/context/store/DefaultMessageStore.java`：
  - 新增 `appendSkillInvocation(String skillName, int skillVersion, int bodyChars)` 方法。
  - 新增 `appendPlanModeChange(PlanModeState from, PlanModeState to)` 方法。
  - 调用现有 `appendUser(...)` 内部分支（role=USER，content 是描述性 metadata）。
  - 不修改 `MessageStore` SPI 接口本身。

**ArchUnit 新增**：

- `pixflow-agent/src/test/java/com/pixflow/agent/architecture/AgentArchitectureTest.java`（`com.tngtech.archunit.junit.AnalyzeClasses` + 6 条 `@ArchTest`）：
  1. `noClasses().that().resideInAPackage("com.pixflow.agent..").should().dependOnClassesThat().resideInAnyPackage("com.pixflow.module.dag..", "com.pixflow.module.task..", "com.pixflow.module.commerce..", "com.pixflow.module.vision..", "com.pixflow.module.imagegen..", "com.pixflow.module.rubrics..", "com.pixflow.module.conversation..")`。
  2. 不依赖 provider 具体适配器（按 FQN 列举）。
  3. 不引用 `harness/tools` 内部类型（除 5 个公开 SPI）。
  4. 不持有 PO（按 FQN 列举）。
  5. `SubagentRunner` 只暴露 `runAsync(...)` 返回 `CompletableFuture`（用 `methodsThat().haveNameMatching("run")` 断言不存在或非 public）。
  6. `DynamicPromptAssembler.assemble` 不在 `ChatModelClient` / `MemoryService` 上调用方法（`noClasses().that().haveSimpleName("DynamicPromptAssembler").should().callMethodWhere..`）。

**测试要点**：

- `DefaultToolRegistryTest`（`pixflow-tools` 模块）：旧构造签名兼容 + `registerDynamic` 后 `visibleDescriptors` 含新工具。
- `DefaultMessageStoreTest`（`pixflow-context` 模块）：新增 2 方法功能。
- `AgentArchitectureTest`：6 条 ArchTest 全绿。

**验证**：

- `mvn -pl pixflow-tools -am test` 全绿。
- `mvn -pl pixflow-context -am test` 全绿。
- `mvn -pl pixflow-agent -am test` ArchUnit 6/6 绿。

### 里程碑 9：测试与验证

**目标**：补全所有单元测试 + 6 个集成测试 + 4 个属性测试，`mvn -pl pixflow-agent -am test` 全部全绿。

**新增文件**：

- `src/test/java/com/pixflow/agent/integration/` 6 个 IT 类。
- `src/test/java/com/pixflow/agent/property/` 4 个属性测试类（用 jqwik）。

**关键实现细节**：

- 集成测试用 Testcontainers：`MySQLContainer` + `GenericContainer(redis)` + `QdrantContainer` + `MinIO container` + mock LLM（自定义 `FakeChatModelClient` 实现 `ChatModelClient`）。
- 属性测试用 jqwik：`@Property` 标注生成器产生随机事件序列，验证不变量。

**测试要点**：

- `SkillEndToEndIT`：写 SKILL.md → 启动同步入表 → 调 `skill__<name>` → 验证 body 返回 + trace metadata 正确。
- `SessionMemoryEndToEndIT`：跑 5 回合 → 触发提取 → 验证 MySQL 单行 content 更新 + Redis 缓存同步 + 断路器切换 fallback。
- `MemoryRecallEndToEndIT`：上传素材包 → 注入 5 条 analysis_insight → 用户提问 → 验证 prompt 含召回片段 + ACTIVE 行被召回。
- `SubagentEndToEndIT`：调 `agent(type=vision)` → child 跑 vision service → 父回合收到 finalText → child 消息**未落 message 表**。
- `CompactionEndToEndIT`：触发 `CONTEXT_LIMIT` → `SummarizationPort.summarize` → fork child 摘要 → 验证活动链改写 + session memory content 不受影响。
- `PlanModeEndToEndIT`：调 `plan` → 验证 visibleTools 不含 `submit_*` → 调 `plan_exit` → 恢复。

**验证**：

- `mvn -pl pixflow-agent -am test` 全部全绿（预计 150+ 测试方法）。
- `mvn -pl pixflow-app -am test` BUILD SUCCESS（agent bean 装配不影响 app 现有测试）。
- `mvn -pl pixflow-agent package` 产物含 `AgentOrchestrator` / `SkillLoader` / `SessionMemoryService` / `SubagentRunner` / `ForkChildSummarizationPort` 等 bean。

### 里程碑 10：文档与依赖图同步

**目标**：把所有跨文档的接缝说明、revision notes、依赖图边补全。

**文档修改清单**：

- `docs/design-docs/harness/context.md` §三 / §四 / §十四：补 `SessionMemoryPort` SPI 定义说明。
- `docs/design-docs/harness/session.md` §十七 暂不考虑段：删除 session memory 条目；revision notes 段加 2026-06-30 条目。
- `docs/design-docs/harness/hooks.md` §十二 / §六：补 3 个新增 hook（TurnStoppedSessionMemoryHook / AssistantMemoryIngestionHook / PreCompactSummaryHook），标注由 agent 模块贡献。
- `docs/design-docs/module-dependency-dag-plan.md`：补 3 条边 `context --> agent` / `hooks --> agent` / `session --> agent`；Wave 5 任务清单把 `agent` 标 `[x]`；revision notes 段加本计划落地说明。
- `docs/design-docs/agent/agent.md` revision notes 段：加 `2026-06-30 / Codex: 完成 pixflow-agent Maven 模块全部里程碑，150+ 测试全绿，详见 exec-plans/agent-module-implementation-plan.md`。

**验证**：

- `git grep "agent"` 在 `module-dependency-dag-plan.md` 出现预期次数。
- 各文档的 revision notes 段都包含 2026-06-30 条目。

## Concrete Steps

### 工作目录

所有命令在 `D:\study\PixFlow`（PowerShell）下运行。

### 里程碑 1 命令序列

```powershell
# 1. 创建 pixflow-agent 目录结构
$dirs = @(
  'pixflow-agent/src/main/java/com/pixflow/agent/prompt/sections',
  'pixflow-agent/src/main/java/com/pixflow/agent/skill',
  'pixflow-agent/src/main/java/com/pixflow/agent/memory',
  'pixflow-agent/src/main/java/com/pixflow/agent/sessionmemory',
  'pixflow-agent/src/main/java/com/pixflow/agent/subagent/tools',
  'pixflow-agent/src/main/java/com/pixflow/agent/summarization',
  'pixflow-agent/src/main/java/com/pixflow/agent/planmode',
  'pixflow-agent/src/main/java/com/pixflow/agent/hooks',
  'pixflow-agent/src/main/java/com/pixflow/agent/config',
  'pixflow-agent/src/main/java/com/pixflow/agent/error',
  'pixflow-agent/src/test/java/com/pixflow/agent/architecture',
  'pixflow-agent/src/main/resources/prompts',
  'pixflow-agent/src/main/resources/db/migration',
  'pixflow-agent/src/main/resources/skills/beauty_watermark'
)
foreach ($d in $dirs) { New-Item -ItemType Directory -Force -Path $d }

# 2. 写 pixflow-agent/pom.xml（按 Progress 阶段 1 依赖清单）

# 3. 改根 pom.xml：加 <module> 与 dependencyManagement 条目

# 4. 写 pixflow-context 的 SessionMemoryPort SPI（3 个文件：SPI 接口 + 2 record）

# 5. 改 docs/design-docs/harness/context.md：补 SPI 说明
# 6. 改 docs/design-docs/exec-plans/module-dependency-dag-plan.md：补边

# 7. 验证
mvn -pl pixflow-context -am test          # 已有 110+ 测试不破
mvn -pl pixflow-agent -am compile         # 编译通过（无源码只有空包）
```

预期：阶段 1 编译 + 已有测试不破。

### 里程碑 2 命令序列

```powershell
# 1. 写 11 个 section 类 + 4 个核心抽象 + 4 个资源文件
# 2. 写 DynamicPromptAssembler

# 3. 验证
mvn -pl pixflow-agent -am test -Dtest='PromptSectionTest,CaffeinePromptSectionCacheTest,DynamicPromptAssemblerTest,PromptRuntimeContextTest'
mvn -pl pixflow-agent -am test
```

预期：60+ 测试方法全绿。

### 里程碑 3 命令序列

```powershell
# 1. 写 SkillFrontmatter + Parser + Skill entity + Repository
# 2. 写 SkillLoader + SkillToolRegistrar + SkillHandler
# 3. 写 resources/skills/beauty_watermark/SKILL.md 示例
# 4. 写 V1__create_skill.sql 迁移

# 5. 改 pixflow-tools/DefaultToolRegistry：支持 registerDynamic
# 6. 验证
mvn -pl pixflow-tools -am test             # 不破
mvn -pl pixflow-agent -am test -Dtest='SkillFrontmatterParserTest,SkillLoaderTest,SkillHandlerTest,SkillToolRegistrarTest'
mvn -pl pixflow-agent -am test
```

预期：90+ 测试方法全绿。

### 里程碑 4 命令序列

```powershell
# 1. 写 4 个 enum/record
# 2. 写 SkuMentionExtractor（含 Redis 缓存封装）
# 3. 写 RrfMerger（含 jtokkit token 估算）
# 4. 写 MemoryRecallPlanner（含 CompletableFuture 并发 + 降级矩阵）

# 5. 验证
mvn -pl pixflow-agent -am test -Dtest='SkuMentionExtractorTest,RrfMergerTest,MemoryRecallPlannerTest'
mvn -pl pixflow-agent -am test
```

预期：115+ 测试方法全绿。

### 里程碑 5 命令序列

```powershell
# 1. 写 3 个 record + entity + repository + cache + service + extractor + updater
# 2. 写 V2__create_session_memory.sql 迁移

# 3. 改 docs/design-docs/harness/session.md：回写 §十七
# 4. 验证
mvn -pl pixflow-agent -am test -Dtest='SessionMemoryServiceTest,SessionMemoryExtractorTest,SessionMemoryUpdaterTest,SessionMemoryThresholdTest'
mvn -pl pixflow-agent -am test
```

预期：140+ 测试方法全绿。

### 里程碑 6 命令序列

```powershell
# 1. 写 SubagentRequest/Result/Type + ChildRuntimeFactory + ChildToolFilter + SubagentPool
# 2. 写 SubagentRunner（异步）+ VisionSubagentTool + ExploreSubagentTool
# 3. 写 ForkChildSummarizationPort + SummaryPromptBuilder

# 4. 验证
mvn -pl pixflow-agent -am test -Dtest='ChildToolFilterTest,SubagentRunnerTest,VisionSubagentToolTest,ExploreSubagentToolTest,ForkChildSummarizationPortTest'
mvn -pl pixflow-agent -am test
```

预期：165+ 测试方法全绿。

### 里程碑 7 命令序列

```powershell
# 1. 写 PlanModeState + PlanModeController + PlanModePrompter + RuntimeStatePlanModeView
# 2. 写 AgentPlanToolHandler + AgentPlanExitToolHandler
# 3. 写 AgentOrchestrator（per-request 入口，15+ 依赖）
# 4. 写 3 个 HookCallback 实现
# 5. 写 AgentProperties + AgentAutoConfiguration

# 6. 验证
mvn -pl pixflow-agent -am test -Dtest='PlanModeControllerTest,AgentPlanToolHandlerTest,AgentPlanExitToolHandlerTest,AgentOrchestratorTest,TurnStoppedSessionMemoryHookTest,PreCompactSummaryHookTest'
mvn -pl pixflow-agent -am test
```

预期：185+ 测试方法全绿。

### 里程碑 8 命令序列

```powershell
# 1. 改 pixflow-tools/DefaultToolRegistry：ConcurrentHashMap + registerDynamic
# 2. 改 pixflow-context/DefaultMessageStore：appendSkillInvocation + appendPlanModeChange

# 3. 写 AgentArchitectureTest（ArchUnit 6 条断言）
# 4. 验证
mvn -pl pixflow-tools -am test             # 不破
mvn -pl pixflow-context -am test           # 不破
mvn -pl pixflow-agent -am test -Dtest='AgentArchitectureTest'
mvn -pl pixflow-agent -am test
```

预期：ArchUnit 6/6 绿，所有测试 190+ 全绿。

### 里程碑 9 命令序列

```powershell
# 1. 写 6 个集成测试 IT
# 2. 写 4 个属性测试
# 3. 验证
mvn -pl pixflow-agent -am test            # 全部 200+ 测试
mvn -pl pixflow-app -am test              # BUILD SUCCESS
mvn -pl pixflow-agent package             # 产物含所有 bean
```

预期：200+ 测试全绿，app 装配不破。

### 里程碑 10 命令序列

```powershell
# 1. 改 docs/design-docs/harness/context.md：补 SessionMemoryPort SPI 说明
# 2. 改 docs/design-docs/harness/session.md：§十七 回写 + revision notes
# 3. 改 docs/design-docs/harness/hooks.md：补 3 个新增 hook
# 4. 改 docs/design-docs/exec-plans/module-dependency-dag-plan.md：补边 + 标 [x]
# 5. 改 docs/design-docs/agent/agent.md：revision notes 段

# 6. 验证
git status                                 # 查看所有改动
git diff docs/                              # 检查文档变更
```

预期：所有文档 revision notes 段含 2026-06-30 条目，依赖图边补齐。

## Validation and Acceptance

### 行为级验收（人工可观察）

下列 11 项验收**全部通过**才算计划完成。每项都对应"用户在外部可观察的"行为，不是"内部属性"。

1. **section 缓存命中**：`mvn -pl pixflow-agent -am test -Dtest=CaffeinePromptSectionCacheTest` 全绿；连续两次 `assemble(ctx)` 在 fingerprint 不变时 section 渲染只发生一次（Caffeine `stats.hitCount` = 1）。
2. **skill 工具化**：`mvn -pl pixflow-agent -am test -Dtest=SkillHandlerTest` 全绿；`SkillHandlerTest` 中 `handle` 返回 `ToolHandlerOutput(body, metadata{skill_name, body_chars})`，body 是 SKILL.md 的完整正文。
3. **skill 同步入表**：启动期跑 `SkillLoaderTest` 的 `syncBuiltIn` 测试 → 验证 `skillRepository.findAllBySource(BUILTIN)` 返回 1 条 `beauty_watermark`；重复跑 → 验证不重复。
4. **记忆召回 RRF 融合**：`RrfMergerTest` 属性测试：4 通道（各 5 条）输入 → 融合后按 RRF 分数排序 + token 预算裁剪 + totalTokens 等于实际累计。
5. **Session Memory 阈值重算**：`SessionMemoryServiceTest` 中"上次 seq=50 当前 head=120" 场景：增量 = 70 条消息，sinceLastExtractTokens = jtokkit 累计的 70 条 token，sinceLastExtractTurn = 5（70 条 / 14 条/回合），`shouldExtract() == true`（turn ≥ 3）。
6. **Session Memory 断路器**：`SessionMemoryServiceTest` 模拟 extractor 连续失败 3 次 → 第 4 次调用 `scheduleExtraction` 时内部走 `SessionMemoryUpdater.extractFallback`，不调 `subagentRunner.runAsync`。
7. **Subagent 异步**：构造测试用 `SubagentRunner`，调 `runAsync(req)` → 返回 `CompletableFuture` 立即可观察（不阻塞）；主测试线程可以 `Thread.sleep(10)` 后 future 已 complete 或正在跑。
8. **Subagent child 不落 message 表**：`SubagentEndToEndIT`（Testcontainers MySQL）跑 5 次 `agent(type=vision)` → child 跑 5 轮 message append → 验证 `message` 表新增 = 5（仅父回合的 `agent` 工具的 tool_result 消息），child 中间消息 0 行。
9. **`SummarizationPort` 触发**：`CompactionEndToEndIT` 触发 `CONTEXT_LIMIT` → context 调 `ForkChildSummarizationPort.summarize` → child 跑 LLM 摘要 → 验证活动链改写（message_compaction 新增 1 条纪元）+ session memory content 不受影响。
10. **Plan 模式三层强制**：`PlanModeEndToEndIT` 调 `plan` 工具 → 验证 `visibleDescriptors(ctx)` 不含 `submit_image_plan` / `submit_imagegen_plan` / `plan` 但含 `plan_exit`；调 `plan_exit` → 恢复。
11. **三个 Hook 接线**：`TurnStoppedSessionMemoryHookTest` + `AssistantMemoryIngestionHookTest` + `PreCompactSummaryHookTest` 全绿；构造真实 `HookPayload` 调 `handle(event, payload)` → 验证返回 `HookResult.noop()` 或 `withMetadata(...)` 且内部副作用（`scheduleExtraction` / `ingestAsync` / metadata 注入）按预期调用。

### 测试命令

```powershell
# 单测 + ArchUnit + 集成测试（Testcontainers 需要 Docker）
mvn -pl pixflow-agent -am test

# 端到端冒烟（pixflow-app 启动后）
mvn -pl pixflow-app spring-boot:run
```

### 验收判定

- `mvn -pl pixflow-agent -am test` 全绿（200+ 测试，含 ArchUnit 6/6）。
- `mvn -pl pixflow-app -am test` BUILD SUCCESS（agent bean 装配不影响 app 现有测试）。
- `mvn -pl pixflow-agent package` 产物含 `AgentOrchestrator` / `SkillLoader` / `SessionMemoryService` / `SubagentRunner` / `ForkChildSummarizationPort` 等 bean。
- 端到端冒烟 11 项人工验收全部通过。

## Idempotence and Recovery

### 幂等性

- 所有 `mvn` 命令可重复运行：创建文件用 `New-Item -ItemType Directory -Force`；schema 资源文件覆盖写不破坏旧内容；Java 源文件用 Edit 工具增量修改；mvn clean install 全链路幂等。
- 启动期 `SkillLoader.syncBuiltIn()` 幂等：基于 `body_hash` 决定是否 UPDATE；同 `name` 重复跑不会产生重复行；`body_hash` 相同则 skip。
- `SessionMemoryService.save()` 幂等：单事务 `INSERT ... ON DUPLICATE KEY UPDATE`，同 `conversationId` 重复 save 不产生重复行；`updated_at` 始终是最新时间。
- `MemoryRecallPlanner.plan()` 不幂等：每次 `recallPlanId` 是新 UUID；但 RRF 融合与 token 截断是确定性算法，相同输入产生相同结果（可重复调用）。
- `SubagentRunner.runAsync()` 不幂等：每次 `childSessionId` 不同；但 child 内部 message chain 是 ephemeral store，重复跑不会污染。

### 失败恢复

- 单测失败时定位到具体测试类，修复后 `mvn -pl pixflow-agent -am test -Dtest=具体类` 重跑。
- 集成测试若 Docker 不可用，按 `infra/storage` 已有的 profile 跳过策略（不视为失败）；与 `design.md §4.1` Windows docker tcp 策略一致。
- `SessionMemoryService` 断路器：连续失败 ≥ 3 次后自动切 fallback；下一回合成功自动重置。
- `SkillLoader` 启动期坏 SKILL.md：记 WARN 日志 + `pixflow.agent.skill.invalid` 指标，不抛异常（保证启动不被一个坏 skill 卡死）；坏文件可后续手动修复后重启应用。
- `SubagentRunner` child LLM 失败：child 异常 → `SubagentResult(isError=true)`，**不冒泡**到父 loop；父回合照常 append + 续轮，与 `agent.md §8.7` 一致。

### 不需要数据迁移

- `pixflow-agent` 模块新建 `skill` 表与 `session_memory` 表，**不修改** 现有表。
- `SessionMemoryPort` SPI 是 `pixflow/context` 模块的纯新增接口，**不破坏** 已有 SPI。
- `DefaultMessageStore` 新增 2 方法，**不修改** 现有方法签名（向后兼容）。

### 回滚策略

- 移除 `pixflow-agent` 模块的 Maven 引用（`<module>pixflow-agent</module>` 与 `<dependency>` 项），其他模块不受影响。
- `DefaultToolRegistry` 的 `registerDynamic` 扩展保留（向后兼容），agent 模块不注入时不影响运行。
- `DefaultMessageStore` 新增 2 方法不调用时不影响运行。
- 删除 `skill` 与 `session_memory` 两张表（DROP TABLE）回滚数据。

## Artifacts and Notes

本段列出本计划的关键代码片段参考（不写实现细节，只列类型签名 / 字段形状），供实施者参考。每个片段都标注它为什么重要。

### SessionMemoryPort SPI 签名

**`pixflow-context/src/main/java/com/pixflow/harness/context/sessionmemory/SessionMemoryPort.java`**（阶段 1 新增）：

```java
public interface SessionMemoryPort {
    Optional<SessionMemoryContent> load(String conversationId);
    void save(String conversationId, SessionMemoryContent content, long lastSummarizedSeq);
    SessionMemoryThreshold computeThreshold(String conversationId, long currentHeadSeq, int currentTurnNo);
    void scheduleExtraction(String conversationId, int turnNo);
}

public record SessionMemoryContent(String markdown, String contentHash) {}
public record SessionMemoryThreshold(
    long lastSummarizedSeq, int coveredTurnCount,
    Instant lastExtractTime, int sinceLastExtractTurn, long sinceLastExtractTokens
) {
    public boolean shouldExtract(long thresholdTokens, int thresholdTurns) {
        return sinceLastExtractTokens >= thresholdTokens
            || sinceLastExtractTurn >= thresholdTurns;
    }
}
```

为什么重要：定义在 `context`、由 `agent` 实现，符合 PixFlow 跨模块 SPI 倒置的统一手法（`TranscriptPort` / `ConfirmationTokenStore` / `ErrorRecorder`）。

### DynamicPromptAssembler 形态

**`pixflow-agent/src/main/java/com/pixflow/agent/prompt/DynamicPromptAssembler.java`**（阶段 2 核心）：

```java
@Component
public final class DynamicPromptAssembler {
    private final List<SectionRenderer> sections;  // 12+ ordered section renderers
    private final PromptSectionCache cache;

    public String assemble(PromptRuntimeContext ctx) {
        var sb = new StringBuilder();
        for (var renderer : sections) {
            var section = renderer.render(ctx);  // PromptSection record
            if (section.body().isEmpty()) continue;  // 空 body 过滤
            var cached = cache.get(section.key(), section.fingerprint());
            var rendered = cached.orElseGet(() -> {
                cache.put(section.key(), section.fingerprint(), section.render());
                return section.render();
            });
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(rendered);
        }
        return sb.toString();
    }
}
```

为什么重要：是 `agent.md §4.5` PromptProvider.systemPrompt(state) 的核心实现；`assemble` 必须是**纯函数**（ArchUnit 6/6 守护不允许内部调 IO）。

### SkillHandler 形态

**`pixflow-agent/src/main/java/com/pixflow/agent/skill/SkillHandler.java`**（阶段 3 核心）：

```java
@Component
public final class SkillHandler implements ToolHandler {
    private final SkillRepository skillRepository;
    private final ToolResultStorage toolResultStorage;

    @Override
    public ToolHandlerOutput handle(ToolInvocation inv) {
        var skillName = inv.toolName().substring("skill__".length());
        var skill = skillRepository.findByName(skillName)
            .orElseThrow(() -> new SkillNotFoundException(skillName));
        var body = skill.body();
        var metadata = Map.<String, Object>of(
            "skill_name", skill.name(),
            "skill_version", skill.version(),
            "body_chars", body.length()
        );
        return new ToolHandlerOutput(body, metadata);  // body > 50KB 由 ToolExecutor 统一外置
    }
}
```

为什么重要：handler **不调 LLM**（与 `agent.md §5.7` 一致），纯读 + 缓存命中；body 长度 > 50KB 走 `ToolExecutor` 统一结果预算（不需在 handler 内自管）。

### SubagentRunner 异步形态

**`pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentRunner.java`**（阶段 6 核心）：

```java
@Component
public final class SubagentRunner {
    private final ChildRuntimeFactory childRuntimeFactory;
    private final ChildToolFilter childToolFilter;
    private final ExecutorService subagentPool;  // core=4/max=16/queue=100

    public CompletableFuture<SubagentResult> runAsync(SubagentRequest req) {
        return CompletableFuture.supplyAsync(
            () -> runSync(req),
            subagentPool
        );
    }

    private SubagentResult runSync(SubagentRequest req) {
        var childState = childRuntimeFactory.build(req);
        var childStore = MessageStore.ephemeral(...);  // 不绑 TranscriptPort
        var childRegistry = childToolFilter.build(req.type());
        var childLoop = new AgentLoop(childState, childStore, ...);
        var sink = CapturingSink.create();
        try {
            childLoop.continueStream(sink);
            return SubagentResult.from(sink.captured(), childState.usage());
        } catch (Exception e) {
            return SubagentResult.error(Sanitizer.sanitizeMessage(e.getMessage()));
        }
    }
}
```

为什么重要：**`runAsync` 是唯一对外 API**（ArchUnit 5/6 守护），`runSync` 是 private；`CompletableFuture.supplyAsync(subagentPool)` 把 child 跑在独立线程池，父线程立即拿到 future。

### AgentOrchestrator 编排形态

**`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java`**（阶段 7 入口）：

```java
@Component  // 实际是普通类，由 conversation 模块在回合入口 new
public final class AgentOrchestrator {
    // 18 个依赖（注入）
    private final AgentLoop agentLoop;
    private final MemoryRecallPlanner memoryRecallPlanner;
    private final SessionMemoryService sessionMemoryService;
    private final DynamicPromptAssembler promptAssembler;
    private final ToolRegistry toolRegistry;
    // ... 其他

    public void streamNewTurn(String conversationId, String prompt,
                              List<Attachment> attachments, AgentEventSink sink) {
        // 1. 校验 conversationId
        // 2. rehydrate（loop + context 自动）
        // 3. 构造 RecallSignal
        var recall = memoryRecallPlanner.plan(buildSignal(prompt, attachments));
        // 4. 构造 PromptRuntimeContext
        var ctx = new PromptRuntimeContext(
            state, conversationId, turnNo, packageId, attachedSkuIds,
            mentionedSkuIds, recentAssistantMessageIds, prompt,
            planModeState, recall, sessionMemoryService.load(conversationId).orElse(null),
            toolRegistry.visibleDescriptors(visibilityCtx)
        );
        // 5. 渲染 systemPrompt + toolSchemas
        var systemPrompt = promptAssembler.assemble(ctx);
        var toolSchemas = toolRegistry.toolSchemas(visibilityCtx);
        // 6. 驱动 loop
        agentLoop.stream(prompt, systemPrompt, toolSchemas, sink);
    }
}
```

为什么重要：步骤 3（**`memoryRecallPlanner.plan` 同步触发**）是用户决策 3 的落地——不在 `assemble` 内、不在 hook 中、而是在 AgentOrchestrator 入口拿当次 prompt + 当次状态。

## Interfaces and Dependencies

### 与上游模块的契约

| 上游模块 | 契约 |
|---|---|
| `module/conversation`（Wave 4） | `TurnDispatchService` 在回合入口 `new AgentOrchestrator(18 deps)`，调 `streamNewTurn(conversationId, prompt, attachments, sink)`；接收 `AgentEvent` 流后桥接 SSE。 |
| `module/dag`（Wave 3） | agent 不直接依赖；`submit_image_plan` 工具 handler 在 `module/dag` 实现，agent 不持有。 |
| `module/commerce` | agent 不直接依赖；`search` / `read` 工具 handler 在 `module/commerce` 实现。 |
| `module/vision` | agent 不直接依赖；`agent(type=vision)` 工具的 child AgentLoop 通过 `SubagentRunner` 间接调 `VisionService.analyze`。 |
| `module/memory` | agent 消费 `MemoryService.prepareContext(MemoryContextRequest) → MemoryContext`（在 `MemoryRecallPlanner.plan` 内并发触发 4 通道）；`MemoryService.ingestAsync(MemoryIngestRequest)`（在 `AssistantMemoryIngestionHook` 内提交）。 |
| `module/file` | agent 消费 `PackageService.getSkuIds(packageId)`（构造 `currentPackageSkuIds` 信号）。 |

### 与下游模块的契约

| 下游模块 | 契约 |
|---|---|
| `harness/loop` | agent 调 `AgentLoop.stream(prompt, systemPrompt, toolSchemas, sink)` / `continueStream`；每轮 `buildForModel` 传入组装好的 `systemPrompt` 与可见 `toolSchemas`。 |
| `harness/tools` | agent 实现 `agent` 工具 + `plan` / `plan_exit` 工具的 `ToolHandler` + `ToolDescriptor` bean；`DefaultToolRegistry` 构造期接 `List<ToolDescriptor>`，启动期 agent 注入 `skill__<name>`。 |
| `harness/context` | agent 实现 `context.SummarizationPort`（destructive compaction 摘要）+ `context.SessionMemoryPort`（累积提取）；agent 不实现 `context.TranscriptPort`（归 session）。 |
| `harness/session` | agent 读 message 序列**经 `context.MessageStore.currentMessages()`**（不直连 MySQL）；agent 在 `MessageStore.appendSkillInvocation` / `appendPlanModeChange` 处间接触发 transcript 持久化。 |
| `harness/hooks` | agent 实现 3 个 `HookCallback`（`TurnStoppedSessionMemoryHook` / `AssistantMemoryIngestionHook` / `PreCompactSummaryHook`）；订阅 `PRE_TOOL_USE` 不写（让 harness/tools 走标准管线）。 |
| `harness/eval` | agent 不写 trace；loop 持有的 `TurnTrace` 句柄由 loop 转投 eval；agent 提供 `PromptSummary` / `RecallSummary` / `SessionMemoryDigest` / `ActiveSkillsDigest` 视图供 trace 抽取。 |
| `harness/permission` | agent 不评估权限；`PlanModeController` 写 `RuntimeState.metadata["planMode"]` 间接影响 `PermissionContext` 构造（由 `pixflow-loop` 的 `PermissionContextFactory` 读）。 |
| `infra/ai` | agent 不直接持有 `ChatModelClient`（经 `AgentLoop` 注入）；`SubagentRunner` 复用父的 `ChatModelClient`（child 跑在父的 model client）。 |
| `infra/storage` | skill body 大结果外置经 `ToolResultStorage`（与其它 tool 一致）；session memory content 大时也走外置。 |
| `infra/cache` | agent 经 `RedissonClient` 读 session memory；SkuMentionExtractor 缓存键 `pixflow:agent:sku_extract:{messageHash}` TTL 24h。 |
| `common` | agent 自治错误用 `AgentErrorCode`（`enum implements ErrorCode`）；文案经 `common.Sanitizer` 脱敏。 |
| `contracts` | agent 不签确认令牌（`submit_image_plan` 工具层零令牌；确认令牌归 `permission.ConfirmationTokenService` + `infra/cache`）。 |

### 关键 SPI 形态

**`PromptProvider`**（`com.pixflow.agent.prompt`）：

```java
public interface PromptProvider {
    String systemPrompt(RuntimeState state);
    List<ToolSchemaView> toolSchemas(RuntimeState state);
}
```

**`PromptSectionCache`**（`com.pixflow.agent.prompt`）：

```java
public interface PromptSectionCache {
    Optional<String> get(String key, String fingerprint);
    void put(String key, String fingerprint, String rendered);
    void invalidate(String key);
    void invalidateAll();
    CacheStats stats();
}
```

**`SkillRepository`**（`com.pixflow.agent.skill`）：

```java
public interface SkillRepository {
    Optional<Skill> findByName(String name);
    List<Skill> findAllBySource(SkillSource source);
    void insert(Skill skill);
    void update(Skill skill);
    void deleteById(String id);
    void deleteByName(String name);
}
```

**`SessionMemoryService`**（实现 `context.SessionMemoryPort`，4 方法见上）。

**`SubagentRunner`**：

```java
public interface SubagentRunner {
    CompletableFuture<SubagentResult> runAsync(SubagentRequest req);
    // 故意不暴露 run() 同步 API
}
```

### 关键配置

```yaml
pixflow:
  agent:
    prompt:
      section-cache:
        max-entries: 1000
    skill:
      enabled: true
      max-body-bytes: 32768
      description-max-chars: 200
      when-to-use-max-chars: 500
    memory:
      recall:
        recent-assistant-turns: 3
        rrf-k: 60
        max-items: 50
        max-tokens: 4000
        sku-patterns: ["^SKU\\d+$", "^[A-Z]{2,}\\d{4,}$"]
        sku-llm-fallback: true
        category-filter-enabled: false
    session-memory:
      threshold:
        tokens: 10000
        turns: 3
      circuit-breaker:
        max-consecutive-failures: 3
      cache:
        ttl-seconds: 3600
      max-content-tokens: 12000
      max-content-bytes: 65536
    subagent:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 100
        keep-alive-seconds: 60
      timeout-seconds: 60
    plan-mode:
      auto-exit-on-completion: false
      keep-draft-on-exit: true
    orchestrator:
      conversation-lock-ttl-seconds: 300
```

## Outcomes & Retrospective

本段在计划最终执行完成时填写。占位内容（执行完成后回填实际数字）：

### 实施完成总结

- `pixflow-agent` Maven 模块按 Java 21 + Spring Boot 3.5.16 落地，源码包 `com.pixflow.agent`。
- 实际里程碑完成时间与进度（按 Progress 段勾选状态）。
- 单元测试 / 集成测试 / ArchUnit 测试的实际数字。
- 端到端冒烟 11 项的实际验收结果。

### 实际产物与原计划的偏离

- 计划项 → 实际实施 → 原因（如有偏离）。
- Testcontainers 集成测试的真实运行环境 / 跳过策略。

### 关键设计决策的回顾

- 12 条决策的回顾（哪些按计划走、哪些需要微调、为什么）。
- 4 个用户决策（Session Memory 选型 / Skill tool_prompt / 记忆召回触发点 / Subagent 异步）的落地回顾。

### 已知 TODO（移交下个迭代）

- 真实 `infra/storage` MinIO 接入 SkillHandler 大结果外置。
- 真实 `infra/ai` provider 接入 SubagentRunner child ChatModelClient。
- Skill 的 PROJECT / TEAM 来源实现。
- Subagent 的 fork 模式实现。
- background agent / 跨进程子 Agent。

## Revision Notes

2026-06-30 / Codex: 新建本 ExecPlan。计划基于 `docs/design-docs/agent/agent.md`（2026-06-30 落地的 Wave 5 Agent 装配层设计权威）撰写，包含 10 个里程碑、6 条 ArchUnit 守护、200+ 测试目标。计划已与用户对齐 4 个生产级细节：Session Memory = MySQL 单行 + 替换式提取（需回写 session.md §十七）、Skill ToolDescriptor 补 `prompt` 字段 + 渲染 `tool_prompt:skill__<name>` 段、记忆召回在 `AgentOrchestrator` 入口同步触发、SubagentRunner 必须异步（`runAsync` 返回 `CompletableFuture`）。计划阶段发现 13 处事实偏差（Surprises & Discoveries 段），主要是 `agent.md` 的 `ModelClient` 等命名与仓库现状 `ChatModelClient` 不一致、Java 21 而非 17、SessionMemoryPort SPI 需先在 `pixflow-context` 补位、`DefaultToolRegistry` 需扩展为支持运行时注册等。
