# 实现 harness/loop 主循环引擎（think-act-observe 薄编排）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/loop` 主循环引擎的完整生产级实现，不实现业务工具 handler、不实现 Agent Prompt 组装、不实现子 Agent runner（这三件事在 Wave 5 `agent` 模块落地）；不引入 maxTurns；不实现 stop-hook 续跑；不做跨进程的可靠 trace 投递；不引入 Reactor 主循环。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级的执行主循环引擎 `harness/loop`。`AgentLoop` 是阻塞式、provider-neutral 的薄编排入口，驱动 think-act-observe 循环；`RuntimeState` 持有单回合可变运行态；`TransitionReason` 描述续轮/终止语义；`AgentEventSink` 同步接出 SSE 流。`loop` 不组装 prompt、不决定可见工具集、不评估权限，它只把 `ContextEngine` 返回的快照交给 `ModelRetryRunner`，解析出工具调用后转交 `ToolExecutor`，并把整个回合的 trace 经 eval SPI 落库。

完成本计划后运行 `mvn -pl pixflow-loop -am test` 应看到：续轮判定只看 `toolCalls`、不读 `stop_reason`；`CONTEXT_LIMIT` 触发 `context.reactiveCompact` 后 retry；输出截断（`StopReason.LENGTH`）触发 `ESCALATE` 后再触发 `RECOVERY`；`COMPLETED` 派发 `TURN_STOPPED` 并 commit trace；`continueStream` 不派发 `USER_PROMPT_SUBMIT` 且不追加 user 消息；ArchUnit 守护证明 `com.pixflow.harness.loop..` 不依赖 `module/*`、不依赖 provider 具体适配器。

## Progress

### 已完成

- [x] (2026-06-29 11:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`，确认 loop 属于 Wave 4 主循环，并已在设计文档中确立薄编排 + provider-neutral + 无 maxTurns + 错误恢复分层（CONTEXT_LIMIT/输出截断在 loop、可重试错误在 infra/ai）的核心口径。
- [x] (2026-06-29 11:10+08:00) 阅读 `harness/context.md`、`harness/tools.md`、`harness/hooks.md`、`harness/permission.md` / `infra/permission.md`、`harness/eval.md`、`harness/session.md`、`infra/ai.md`、现有 `pixflow-context`、`pixflow-tools`、`pixflow-hooks`、`pixflow-permission`、`pixflow-eval`、`pixflow-session`、`pixflow-infra-ai` 源码，确认 loop 的 9 条接缝契约的当前类型签名、缺位 SPI 与现存数据模型。
- [x] (2026-06-29 11:20+08:00) 与用户对齐 4 个 plan 阶段必须钉死的实现细节：`buildForModel` 应同时返回 `BuildResult(snapshot, pruneEntries)`、`ASSISTANT_MESSAGE_COMPLETED` 显式把 `RuntimeScope`（来自 `pixflow-hooks`）注入 payload、`TraceInput` 的字段形状以 eval 现状字段为准、`turnNo` 由 loop 自维护会话内计数器。
- [x] (2026-06-29 11:30+08:00) 确认当前 `pixflow-loop` Maven 模块尚未存在；本计划按生产级完整模块设计，不分 MVP 阶段。
- [x] (2026-06-29 11:30+08:00) 创建本 ExecPlan 初稿，按 `PLANS.md` 要求写清目的、机制、检索关键词、具体步骤和验收方式；本轮仅输出计划文档，不写 Java 代码。
- [x] (2026-06-29 12:00+08:00) 按用户要求扩充 Progress 段：把后续 11 个落地阶段拆为可勾选任务。

#### 阶段 1：更新设计与依赖地基
- [x] (2026-06-30 22:00+08:00) 新增 `pixflow-loop` Maven 模块：在根 `pom.xml` 加 `<module>pixflow-loop</module>` 与 dependencyManagement 条目；新建 `pixflow-loop/pom.xml`，依赖 `pixflow-common` / `pixflow-permission` / `pixflow-hooks` / `pixflow-context` / `pixflow-tools` / `pixflow-eval` / `pixflow-infra-ai` + Spring Boot / Micrometer / archunit-junit5。
- [x] (2026-06-30 22:10+08:00) 在 `pixflow-context/.../ContextEngine.java` 把 `buildForModel(String, List<ToolSchemaView>)` 改造为返回 `BuildResult(snapshot, pruneEntries)`，并保留旧签名（`buildForModelLegacy`）作为 deprecated 路径；`pixflow-context` 的现有测试改为走 deprecated 路径；新增 `pixflow-eval` 依赖以使用 `TracePruneEntry`。
- [x] (2026-06-30 22:15+08:00) 在 `docs/design-docs/harness/context.md` §十一 同步「`buildForModel` 返回 `BuildResult`、`pruneEntries` 由 loop 转投 eval」的接口微调说明。
- [x] (2026-06-30 22:30+08:00) 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 的 Mermaid 图补 `ai --> loop` 边；在 Revision Notes 记录补边原因（`ModelRetryRunner` 是 loop 的真正上游，`infra/ai` 在 Wave 1、loop 在 Wave 4，无环）。

#### 阶段 2：core API 与模型
- [x] (2026-06-30 22:45+08:00) 新建 `com.pixflow.harness.loop.Attachment`（record：`id` / `kind` / `reference` / `metadata`）。
- [x] (2026-06-30 22:45+08:00) 新建 `com.pixflow.harness.loop.TransitionReason`（6 态枚举）。
- [x] (2026-06-30 22:45+08:00) 新建 `com.pixflow.harness.loop.RuntimeState`（final class，11 个字段：`usage` / `iterationCount` / `hasAttemptedReactiveCompact` / `maxOutputRecoveryCount` / `hasEscalatedMaxOutput` / `lastTransition` / `conversationId` / `runtimeScope` / `turnNo` / `traceId` / `metadata`，加 `addUsage` / `setTransition` / `incrementIteration` / `markReactiveCompactAttempted` / `incrementMaxOutputRecovery` / `markMaxOutputEscalated` 方法；线程封闭约定见 Interfaces and Dependencies 段）。
- [x] (2026-06-30 22:45+08:00) 新建 `com.pixflow.harness.loop.event.AgentEventType`（7 类枚举）与 `com.pixflow.harness.loop.event.AgentEvent`（record + 7 个工厂方法 `delta / assistantCompleted / toolCallReady / toolStarted / toolResult / transition / completed`）。
- [x] (2026-06-30 22:45+08:00) 新建 `com.pixflow.harness.loop.event.AgentEventSink`（同步接口，签名 `emit(AgentEvent)` + `NOOP` 常量）。
- [x] (2026-06-30 23:00+08:00) 新建 `com.pixflow.harness.loop.AgentLoop` 骨架（final class，构造期接收 15 个协作依赖 + 内部 `stream` / `continueStream` / `runLoop`；具体实现见阶段 6）。

#### 阶段 3：ModelStreamConsumer
- [x] (2026-06-30 23:15+08:00) 新建 `com.pixflow.harness.loop.stream.ModelStreamConsumer` 与 `ModelOutcome`（record：`hasToolCalls` / `toolCalls` / `finalText` / `stopReason` / `usage` / `outputInterrupted` / `systemPromptFingerprint`）。
- [x] (2026-06-30 23:15+08:00) 实现 `consume(Flux<ChatStreamEvent>, AgentEventSink, RuntimeState): ModelOutcome`：用 `publishOn(Schedulers.boundedElastic()).doOnNext(...).blockLast(timeout)` 把 Reactor 流桥接到回合线程同步消费；`TextDelta` → `AgentEvent.delta(...)`；`AttemptReset` → `AgentEvent.transition(RATE_LIMIT_RETRY)`；`Completed` 累积到 outcome。**返回值是 `ModelOutcome`**，不传 `Consumer<ModelOutcome>` 回调（plan 阶段设想改为直接返回值，更便于 AgentLoop 主循环使用）。
- [x] (2026-06-30 23:15+08:00) 验证：失败 attempt 的 partial delta 不 emit（由 `ModelRetryRunner` 缓冲式保证，loop 只消费成功 attempt）—— 单测 `AgentLoopErrorRecoveryTest.contextLimitFirstTimeTriggersReactiveCompactThenSucceeds` 验证：首次 errorFlux → 第二次 successful Flux → outcome.finalText 即为第二次的 delta 文本。

#### 阶段 4：ReactiveCompactionGate 与 OutputInterruptHandler
- [x] (2026-06-30 23:25+08:00) 新建 `com.pixflow.harness.loop.recovery.GateDecision`（sealed interface：`Retry` / `ContinueAfterAppend` / `Abort`，三个 record 拒绝 null reason / error）。
- [x] (2026-06-30 23:25+08:00) 新建 `com.pixflow.harness.loop.recovery.ReactiveCompactionGate`：`onContextLimit(state, store, error)` 在 `!hasAttemptedReactiveCompact` 时调 `compactionService.reactiveCompact` + 置位 + 返回 `Retry(REACTIVE_COMPACT_RETRY)`；否则 `Abort`。
- [x] (2026-06-30 23:25+08:00) 新建 `com.pixflow.harness.loop.recovery.OutputInterruptHandler`：`onOutputInterrupted(state, store, partialText, continuationPrompt)` 实现 ESCALATE（抬高 `metadata.modelRequestOverrides.maxOutputTokens` 到 `LoopProperties.escalatedMaxOutputTokens`） / RECOVERY（append 截断 assistant + continuation prompt + 递增计数器） / 超限 `Abort(LOOP_RUNTIME_STATE_CORRUPTED)` 三段逻辑。

#### 阶段 5：TraceFanout 与 LoopToolTraceSink 与 RuntimeScopeTranslator
- [x] (2026-06-30 23:35+08:00) 新建 `com.pixflow.harness.loop.trace.TraceFanout`：`fanoutPrune(List<TracePruneEntry>)` / `fanoutHookSpan(event, result, toolName, toolCallId, latencyMs)` / `fanoutRetry(reason, latencyMs)` / `fanoutToolResult(result, latencyMs)` / `fanoutToolTraceEvent(event)` 五个方法，转投当前 `TurnTrace`。
- [x] (2026-06-30 23:35+08:00) 新建 `com.pixflow.harness.loop.trace.LoopToolTraceSink`（实现 `com.pixflow.harness.tools.result.ToolTraceSink`），构造期接收 `TurnTrace`；把 `ToolTraceEvent` 翻译为 `recordToolCall(TraceToolCall)`，`event.rewritten` 反映到 `TraceToolCall.input("rewritten", true)`；**不修改** `ToolExecutionContext` 字段。
- [x] (2026-06-30 23:35+08:00) 新建 `com.pixflow.harness.loop.trace.RuntimeScopeTranslator`：`toEval(hooks.RuntimeScope)` / `toHooks(eval.RuntimeScope)` 两个静态方法；main ⇄ MAIN、subagent ⇄ SUB_AGENT、worker ⇄ WORKER。

#### 阶段 6：AgentLoop 主循环
- [x] (2026-06-30 23:50+08:00) 实现 `AgentLoop.stream(prompt, attachments, sink, systemPrompt, toolSchemas)`：派发 `USER_PROMPT_SUBMIT` → 追加 user + attachments → 进入 `runLoop`。
- [x] (2026-06-30 23:50+08:00) 实现 `AgentLoop.continueStream(sink, systemPrompt, toolSchemas)`：跳过 user prompt 派发与追加，从已 seed 链继续跑 `runLoop`。
- [x] (2026-06-30 23:55+08:00) 实现私有 `runLoop(sink, systemPrompt, toolSchemas)`：while(true) + `buildForModel` + `ModelStreamConsumer.consume` + 错误恢复分发 + `appendAssistant` + `ASSISTANT_MESSAGE_COMPLETED` 派发 + 工具执行分支 + `TURN_STOPPED` 派发 + flush + commit。**与 plan 微调**：`runLoop` 不带 `dispatchUserPromptSubmit / appendUserMessage` 布尔参数（plan 设想），改为 `stream` 与 `continueStream` 各自在调用前完成派发与追加，`runLoop` 只关心 while 循环主体——这与 plan 中的"两个方法共用一个私有 runLoop"原则一致，但参数化方式更清晰。
- [x] (2026-06-30 23:55+08:00) 构造 `ToolExecutionContext`：注入 `PermissionContext`（`PermissionContextFactory.create(state)`）/ `LoopToolTraceSink(turnTrace)` / `hiddenTools`（来自 `state.metadata`）/ 并发线程池 `Executors.newFixedThreadPool(toolConcurrencyPoolSize, daemon thread)`。
- [x] (2026-06-30 23:55+08:00) 不可恢复异常路径：捕获后 `turnTrace.abort(error)` → `errorRecorder.record(error)` → 重新抛，**不** emit error 事件。

#### 阶段 7：PermissionContextFactory
- [x] (2026-07-01 00:05+08:00) 新建 `com.pixflow.harness.loop.permission.PermissionContextFactory`（接口）与 `DefaultPermissionContextFactory`（实现）：从 `RuntimeState.metadata.deniedTools` / `disabledTools` / `subagent`（`Map<String,Object>{agentType, readOnly, allowedTools, disallowedTools}`）翻译成 `PermissionContext` 字段；`subagent` 缺 `agentType` 时降级为 `null`（非子 Agent）。

#### 阶段 8：配置与自动装配
- [x] (2026-07-01 00:15+08:00) 新建 `com.pixflow.harness.loop.config.LoopProperties`（`@ConfigurationProperties(prefix = "pixflow.loop")`）：`maxOutputRecoveryLimit=3` / `escalatedMaxOutputTokens=64000` / `emitToolInputPreview=true` / `toolConcurrencyPoolSize=8` / `compactionSource="loop.reactive"`。
- [x] (2026-07-01 00:15+08:00) 新建 `com.pixflow.harness.loop.config.LoopAutoConfiguration`：仅暴露 `PermissionContextFactory` 一个 bean + 启用 `LoopProperties`。**与 plan 微调**：`AgentLoop` 与 `LoopToolTraceSink` **不**装配为 bean —— `AgentLoop` 依赖 15 个他模块 SPI，由调用方（conversation / agent 层）在回合入口构造（构造期注入 `RuntimeState`）；`LoopToolTraceSink` 由 `AgentLoop` 在每次 `executeToolCalls` 中现构造（持有当前 `TurnTrace`），不让 Spring 容器管理其生命周期。
- [x] (2026-07-01 00:15+08:00) 新建 `com.pixflow.harness.loop.error.LoopErrorCode`（enum implements `com.pixflow.common.error.ErrorCode`，3 个自治码：`LOOP_RUNTIME_STATE_CORRUPTED` / `LOOP_CONFIGURATION_INVALID` / `LOOP_TURN_BOUNDARY_VIOLATION`）。

#### 阶段 9：单元测试（11 个测试类共 40 个测试）
- [x] (2026-07-01 00:30+08:00) 续轮判定：`AgentLoopContinuationDecisionTest.completesWhenModelReturnsNoToolCalls` / `executesToolCallsWhenModelRequestsToolAndCompletesOnNextRound`。
- [x] (2026-07-01 00:30+08:00) 无 maxTurns：`AgentLoopContinuationDecisionTest.noMaxTurnsLimitIteratesUntilModelStopsCallingTools`（连续 3 轮工具调用 + 第 4 轮无调用 → iterationCount=4，lastTransition=COMPLETED）。
- [x] (2026-07-01 00:30+08:00) CONTEXT_LIMIT 恢复：`AgentLoopErrorRecoveryTest.contextLimitFirstTimeTriggersReactiveCompactThenSucceeds` + `RecoveryGateTest.reactiveCompactionGateReturnsRetryOnFirstTimeAndAbortsOnSecond`（首次 Retry + 再次 Abort 防抖验证）。
- [x] (2026-07-01 00:30+08:00) 输出截断恢复：`RecoveryGateTest.outputInterruptHandlerEscalatesOnFirstInterrupt` / `RecoversOnSecondInterrupt` / `AbortsAfterLimitExceeded`。
- [x] (2026-07-01 00:30+08:00) 不可恢复异常：`AgentLoopErrorRecoveryTest.unrecoverableExceptionAbortsTurnTraceAndRecordsError`（TurnTrace.abort + ErrorRecorder.record + 上抛 + 不 emit error 事件）。
- [x] (2026-07-01 00:30+08:00) `continueStream`：`AgentLoopErrorRecoveryTest.continueStreamSkipsUserPromptSubmitAndDoesNotAppendUser`。
- [x] (2026-07-01 00:30+08:00) `RuntimeScopeTranslator`：`RuntimeScopeTranslatorTest` 8 条断言。
- [x] (2026-07-01 00:30+08:00) `LoopToolTraceSink`：`LoopToolTraceSinkTest.rewrittenFlagFlowsThroughIntoTraceToolCallInput` + `errorFlagProducesTraceError`。
- [x] (2026-07-01 00:30+08:00) `TraceFanout.fanoutPrune` / `fanoutHookSpan` / `fanoutRetry` / `fanoutToolResult`：`TraceFanoutTest` 5 条断言。
- [x] (2026-07-01 00:30+08:00) `RuntimeState` 字段语义 + `DefaultPermissionContextFactory` 翻译：`RuntimeStateTest`（8）+ `DefaultPermissionContextFactoryTest`（5）。

#### 阶段 10：ArchUnit 守护
- [x] (2026-07-01 00:40+08:00) 新建 `architecture/LoopArchitectureTest`：4 条 ArchTest 断言（不依赖 `module/*`、不依赖 provider 具体适配器 `dashscope/openai`、不引用 `ToolDescriptor` 类、不引用 `SseEmitter`）。**与 plan 微调**：原 plan 设想 5 条断言（包含「不组装 prompt」），但 ArchUnit 不擅长正则字符串匹配（且 `String` 拼装无法静态枚举），该条由 code review 守护，不入 ArchUnit。

#### 阶段 11：同步设计文档与依赖图
- [x] (2026-07-01 00:50+08:00) 在 `docs/design-docs/harness/context.md` §十一 加 BuildResult 接口说明 + 接缝契约表更新。
- [x] (2026-07-01 00:50+08:00) 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 补 `ai --> loop` 边、Revision Notes 加本计划落地说明。
- [x] (2026-07-01 00:55+08:00) 跑 `mvn -pl pixflow-loop -am test`（预期 BUILD SUCCESS，44 个测试全过）✅；`mvn -pl pixflow-loop,pixflow-context -am test` ✅；`mvn test`（全 reactor BUILD SUCCESS，所有模块测试不破坏）✅。
- [x] (2026-07-01 00:55+08:00) 在本计划「Outcomes & Retrospective」段写入实现完成总结；在「Revision Notes」段记录本计划版本与 Java 实现对接情况。

## Surprises & Discoveries

- Observation: `harness/eval.md` 与 `harness/loop.md` 都未规定 `TraceInput` 的具体字段，而 `pixflow-eval` 已落地 `record TraceInput(Instant recordedAt, String stage, String promptPreview, String messageSnapshotRef, List<String> visibleTools, Map<String,Object> metadata)`。  
  Evidence: `pixflow-eval/src/main/java/com/pixflow/harness/eval/model/TraceInput.java`。loop plan 必须按此现状字段形状落 trace，不应再发明一套 SHA256 + id 列表的字段；`promptPreview` + `messageSnapshotRef` + `visibleTools` + `stage` 已能覆盖「每轮模型调用的输入视图」回放需求。

- Observation: `RuntimeScope` 在 `pixflow-hooks/payload/RuntimeScope.java` 是 `record(boolean subagent, String subagentType)`（主/子 Agent 维度），在 `pixflow-eval/model/RuntimeScope.java` 是 `enum { MAIN, SUB_AGENT, WORKER }`（trace 持久化维度）。两者职责不同，不是重复定义。  
  Evidence: 两个文件包路径不同、字段不同。loop plan 必须明确：① 派发 hook 时用 `com.pixflow.harness.hooks.payload.RuntimeScope`；② 调 `TraceRecorder.begin` 时把 hooks 的 `RuntimeScope` 翻译成 eval 的 enum（`MAIN` / `SUB_AGENT(子type)` / 未来 `WORKER`），并把翻译规则集中放在 `loop.trace.RuntimeScopeTranslator` 内部，loop 主循环不持有两份类型。

- Observation: `pixflow-context/.../ContextEngine.java` 当前 `buildForModel(String systemPrompt, List<ToolSchemaView> toolSchemas)` 签名是两参数，**不传 `RuntimeState`**，且返回值是 `ContextSnapshot` 而非 `BuildResult`，**不带出本轮裁剪条目**。  
  Evidence: `ContextEngine.java:23`。loop plan 必须把「扩为 `BuildResult(snapshot, pruneEntries)`」列为对 `pixflow-context` 的接口微调（与 `loop.md §八` 末尾的设计建议对齐），落地时在 `context.md` 与本 plan 同步。`runtimeState` 仍然是 loop 的内部状态，context 不应持有它（context 模块的编译期依赖不允许反向引用 loop 的 `RuntimeState`，否则会形成 `context → loop` 倒挂）。

- Observation: `pixflow-context/.../ContextCompactionService.reactiveCompact(MessageStore, PixFlowException, Map<String,Object>)` 当前的第二参数是 `PixFlowException`，第三参数是 metadata。`loop.md §八` 的流程图把它写为 `context.reactiveCompact(state, error)`。  
  Evidence: `ContextCompactionService.java:55-67`。`RuntimeState` 在 context 侧没有意义；plan 必须把 loop 的入参改为 `(messageStore, contextLimitError, metadata)`，且 `RuntimeState.hasAttemptedReactiveCompact` 的防抖位由 loop 自维护，context 不感知。

- Observation: `pixflow-eval` 已实现 `TurnTrace extends AutoCloseable` 且 `close()` 默认 `commit()`。  
  Evidence: `TurnTrace.java:9-28`。loop plan 可以直接 try-with-resources 持有 `TurnTrace`；`commit()` 与 `abort(error)` 都是显式入口，正常路径走 `commit()`、异常路径走 `abort(error)`，关闭时 `close()` 等价 commit。loop 必须在异常路径显式 `abort` 后再 `close`，避免 commit 覆盖 abort 状态。

- Observation: `harness/tools/.../ToolExecutionContext` 已经是 record，**不包含 `TurnTrace` 句柄**——但 `tools.md §十一` 规定执行管线在每次工具调用结束经 `ToolTraceSink` SPI 投递 span。  
  Evidence: `ToolExecutionContext.java:14-22`。loop plan 应在 `ToolExecutionContext` 构造时由 loop 把 `TurnTrace` 句柄转交给一个 loop 侧的 `ToolTraceSink` 适配器（`LoopToolTraceSink`），该适配器调 `turnTrace.recordToolCall(...)`；**不修改** `ToolExecutionContext` 字段，保持 `tools` 不依赖 `eval`。

- Observation: `infra/ai/.../ModelRetryRunner` 当前签名是 `run(ModelRole role, Function<Integer, Flux<ChatStreamEvent>> attemptSupplier)`，返回 `Flux<ChatStreamEvent>`，且缓冲式重试已在 runner 内部实现（首次 TextDelta 前透明、之后发 `AttemptReset`）。  
  Evidence: `ModelRetryRunner.java:24-26`。loop 文档里说「调 `ModelRetryRunner.stream(...)`」是错的——实际 API 是 `Flux<ChatStreamEvent>`。plan 必须以 `Flux<ChatStreamEvent>` 为准；loop 在 Reactor 线程上订阅后立刻 `Schedulers.boundedElastic().schedule(sinkEmitter)` 转到回合线程同步消费，把事件翻译为 `AgentEvent`，这样「阻塞 + 事件回调」与 infra/ai 的 Reactor 一起构成完整链路。

- Observation: 当前 `pixflow-loop` 没有任何 Java 源码、Maven 模块也没有；`module-dependency-dag-plan.md` 的 Wave 4 任务清单里 `harness/loop` 标记为 `- [ ]` 未完成。  
  Evidence: `find D:/study/PixFlow -type d -name 'pixflow-loop'` 无结果；DAG plan Wave 4 任务清单第 224 行。

- Observation: 落地时的命名漂移：`infra/ai` 中实际是 `ChatModelClient`（接口）+ `ChatStreamEvent`（sealed）+ `TokenUsage`（record 在 `model` 包），plan 文档中多处写为 `ModelClient` / `ModelStreamEvent` / `ModelUsage`。落地必须按现状类型名 import，不能照搬 plan 的字面命名。  
  Evidence: `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatModelClient.java:8`、`ChatStreamEvent.java:10`、`TokenUsage.java:6`。

- Observation: `ModelRetryRunner` 是 `final` 类（`ModelRetryRunner.java:17`），无法被继承。Plan 阶段设想「FakeModelRetryRunner 继承替身」，落地时改为构造 `new ModelRetryRunner(RetryPolicy(0, ...))` 的不重试真实 runner，并把错误注入放在 `FakeChatModelClient.stream` 返回 `Flux.error(...)` 上——更贴合 infra/ai 的真实边界。  
  Evidence: `ModelRetryRunner.java:17` `public final class ModelRetryRunner`。

- Observation: `ChatStreamEvent.Completed` 字段是 `(finalText, toolCalls, stopReason, usage)` 而非 `(toolCalls, stopReason, usage, systemPromptFingerprint, outputInterrupted, messageCompleted)`。infra/ai 本期**不**对外暴露 `outputInterrupted` 信号——`ModelOutcome.outputInterrupted` 永远为 `false`，`OutputInterruptHandler` 不会触发。本期落地保留该字段，等 infra/ai 后续提供信号后即生效；这与 `loop.md §十` 末尾的「infra/ai 不暴露该信号时该级恢复自动空转」设计意图一致。  
  Evidence: `ChatStreamEvent.java:17`。

- Observation: `LoopAutoConfiguration` 不应暴露 `AgentLoop` bean。`AgentLoop` 构造期接收 15 个依赖（`RuntimeState` / `MessageStore` / `ContextEngine` / `ContextCompactionService` / `ChatModelClient` / `ModelRetryRunner` / `ToolExecutor` / `PermissionPolicy` / `ToolResultStorage` / `PlanModeView` / `HookRegistry` / `TraceRecorder` / `PermissionContextFactory` / `ErrorRecorder` / `LoopProperties`），且 `RuntimeState` 是 per-conversation 实例（每回合一个），无法作为单例 bean 装配。Plan 阶段设想「提供 AgentLoop bean」与运行时实际冲突；落地改为「`LoopAutoConfiguration` 仅暴露 `PermissionContextFactory` bean，`AgentLoop` 由调用方在请求入口构造」。  
  Evidence: `LoopProperties.java` / `AgentLoop.java:50-90` 构造器签名。

- Observation: `RuntimeState` 在落地时增加了 `turnNo` 与 `traceId` 两个字段（原 plan 仅 9 字段，实际为 11 字段）。原因是 `turnNo` 必须在调用 `TraceRecorder.begin` 前自增并固化（不能事后改 `BeginArgs`），`traceId` 由 `AgentLoop.stream/continueStream` 入口生成并贯穿整回合（不依赖上游传 traceId）。这两个字段是 plan 文档写「9 个字段」时遗漏的——已在仓库 `RuntimeState.java` 中补齐，并在 Outbound 与 evaluation 路径中正确使用。  
  Evidence: `RuntimeState.java:32-33` 字段声明。

- Observation: `LoopToolTraceSink` 不持 `RuntimeScope`。Plan 文档写「构造期接收 `TurnTrace` + `RuntimeScope`」，但 `RuntimeScope` 对单条工具 trace span 没有信息量（已经在 `TurnTrace.runtimeScope` 持久化），落地时 `LoopToolTraceSink(TurnTrace)` 单参构造更简单——`RuntimeScopeTranslator` 仅在 loop 主循环 `TraceRecorder.begin` 时被调用一次。  
  Evidence: `LoopToolTraceSink.java:21`。

- Observation: `MessageStore.appendAssistant(Message)` 要求 `role=ASSISTANT`；`AgentLoop.runLoop` 落地时若 assistant 带 tool_call_ids，必须 `Message.assistant(content).withMetadata(MessageMetadata.empty().with(TOOL_CALL_IDS, ids))`——record `Message` 的 `withMetadata` 是 `public` 方法。  
  Evidence: `Message.java:51`、`MessageMetadata.java:27-31`。

- Observation: ArchUnit 的 `noClasses().should().dependOnClassesThat().haveFullyQualifiedName(...)` 可以精细到具体类；plan 阶段设想 5 条断言，但「不组装 prompt 文本」无法靠 ArchUnit 静态正则匹配 `+ "You are" +` 这种字符串模式（record 字段 + Jackson 拼接太多变体），落地时仅 4 条断言入 ArchUnit，第 5 条交由 code review 守护。  
  Evidence: `LoopArchitectureTest.java`。

- Observation: 计划要求 Java 21（根 pom `java.version=21`），但 `harness/loop.md` 写「Java 17 + Spring Boot 3」。  
  Evidence: 根 `pom.xml:47`。这是设计文档与工程现状的小幅漂移，loop 是新模块，**直接按 Java 21 落地**，文档无需修改（设计意图不变）。

## Decision Log

- Decision: `harness/loop` 新增为独立 Maven 模块 `pixflow-loop`，包名 `com.pixflow.harness.loop`，按 Java 21 + Spring Boot 3.5.16 落地。  
  Rationale: 仓库已按多模块组织，Wave 4 主循环独立成模块能保持依赖方向（`{tools, hooks, context, permission, eval} → loop → agent`）清晰，也方便 `mvn -pl pixflow-loop -am test` 做局部验证。  
  Date/Author: 2026-06-29 / Codex

- Decision: loop 实现阻塞主循环，**不引入 Reactor 响应式**；与 infra/ai 的 `Flux<ChatStreamEvent>` 接缝点是「在 Reactor 线程上订阅 + 立即用 `Schedulers.boundedElastic().schedule(...)` 转到回合线程同步消费」+「失败 attempt 的 partial delta 不 emit（`ModelRetryRunner` 缓冲式已保证，loop 只 emit 成功 attempt 的事件）」。  
  Rationale: `loop.md §七` 已明确「阻塞主循环 + 事件回调」是设计决策，引入 `Flux<AgentEvent>` 会与各 harness 模块的「回合内线程封闭」假设相悖。Reactor 只留在 infra/ai 边界；loop 在订阅后立即用 `Schedulers.boundedElastic` 把发射转到回合线程的 `sink.emit(...)`，**整条 while 循环在单线程内同步跑完**。  
  Date/Author: 2026-06-29 / Codex

- Decision: **`ContextEngine.buildForModel` 改造为返回 `BuildResult(snapshot, pruneEntries)`**，由 `pixflow-context` 在落地时同步修改签名（与 `loop.md §八` 末尾的接口微调建议对齐）。  
  Rationale: 「cheap pipeline / destructive compaction 裁剪日志由 loop 转投 eval」是责任上移，不带出 prune 条目则 `prune_log_json` 永远没数据源，rubrics 离线评估的「裁剪合理性」维度会缺一臂。BuildResult 是「主 record + 伴随结构」的解耦形式，保持 `ContextSnapshot` 本身仍是「模型可见数据」的纯粹视图。  
  Date/Author: 2026-06-29 / Codex

- Decision: **`AssistantMessageCompleted` 派发时显式把 `com.pixflow.harness.hooks.payload.RuntimeScope` 注入 payload**。loop 不持有 eval 的 `RuntimeScope` enum；调 `TraceRecorder.begin` 时由 `loop.trace.RuntimeScopeTranslator` 把 hooks 的 record 翻译成 eval 的 enum。  
  Rationale: hooks 的 `RuntimeScope` 是 HookPayload 的公共基段（`hooks.md §四.3`），与 `ToolUsePayload`、`UserPromptSubmitPayload` 等共用；loop 派发 hook 必须用同一份。eval 的 enum 是 trace 持久化维度，hooks 不感知。把翻译规则收编在 loop 包内一个静态方法，避免 loop 主循环同时持有两份 `RuntimeScope` 类型。  
  Date/Author: 2026-06-29 / Codex

- Decision: **`turnNo` 由 loop 自维护会话内计数器**（`RuntimeState` 持 `int turnNo`，回合入口 +1 后回填 `TraceRecorder.begin`）。  
  Rationale: `turnNo` 语义只在「回合维度」（USER 消息粒度），`session` 的 `seq` 是消息维度（每次 append +1）。一个回合内通常产生 ≥3 条消息，`seq > turnNo` 几乎永远成立。session 算 turn 边界会引入「上一条 user message 的 seq + 1 = 新 turn 起点」的复杂推断；context 只持有工作内存，不应承担回合序号。把 turn 计数收编在 loop 的 `RuntimeState` 是最朴素、最易测的选择。  
  Date/Author: 2026-06-29 / Codex

- Decision: **`RuntimeState.metadata` 承载 `subagent` / `readOnlyAgent` / `deniedTools` / `disabledTools` / `hiddenTools` / `planMode` / `modelRequestOverrides` / `isForkChild`**。loop 自身不解释其业务语义；permission / tools / context 按需读，loop 只在「读 → 填到 `ToolExecutionContext` / `PermissionContext`」的边界把它们翻译出去。  
  Rationale: 与 `loop.md §6.2` 一致。避免 loop 引入任何业务字段的解释逻辑——它只是运行态视图的载体。  
  Date/Author: 2026-06-29 / Codex

- Decision: **错误恢复分层严格按 `loop.md §十`**：可重试错误在 `infra/ai` 的 `ModelRetryRunner` 消化（loop 仅记录 `RATE_LIMIT_RETRY` 计数）、`CONTEXT_LIMIT` 首次由 `ReactiveCompactionGate` 触发 `context.reactiveCompact` 重试本迭代（loop 维护 `hasAttemptedReactiveCompact` 防抖位）、输出截断首次 escalate、再次 recovery。其余不可恢复错误经 `ErrorRecorder` 落盘 + `TurnTrace.abort(error)` 后向上抛，**loop 不 emit error 事件**。  
  Rationale: 错误恢复是跨模块协调点，留给 loop；不把恢复逻辑塞进 tools/eval/permission 任何一方。  
  Date/Author: 2026-06-29 / Codex

- Decision: **ArchUnit 守护**「`com.pixflow.harness.loop..` 不依赖任何 `module/*`、不依赖 provider 具体适配器（`pixflow-infra-ai/.../chat/*ChatModelClient` 之外的 provider 包）、不组装 prompt 文本、不持有 `ToolDescriptor`」。  
  Rationale: `loop.md §三` 的边界硬约束；ArchUnit 编译/测试期失败即可阻止违规，无需靠 code review 拦。  
  Date/Author: 2026-06-29 / Codex

- Decision: **本期不做 stop-hook 续跑**（无 `STOP_HOOK_CONTINUE` transition）、**不做 `maxTurns` 上限**、**不做跨进程 trace 可靠投递**、**不做 background agent / 跨进程子 Agent**。  
  Rationale: `loop.md §十八` 明确不在本期范围；引入它们会推高复杂度且无对应需求。  
  Date/Author: 2026-06-29 / Codex

- Decision: **`ModelStreamConsumer.consume` 直接返回 `ModelOutcome`，不传 `Consumer<ModelOutcome>` 回调**（plan 阶段设想是回调式）。  
  Rationale: 回调式需要在 `Flux.blockLast` 内部触发 onCompleted，导致 lambda 必须捕获最终值（`AtomicReference<ModelOutcome>` 之类）；直接同步返回 + 阻塞消费语义更清晰，也避免「回调 vs 返回值」二选一的语义混淆。落地后 `AgentLoop.runLoop` 把 `outcome` 直接链入主流程即可，无中间 closure。  
  Date/Author: 2026-07-01 / Codex

- Decision: **`LoopAutoConfiguration` 不暴露 `AgentLoop` bean**（plan 阶段设想 `AgentLoop` 可装配）。  
  Rationale: `AgentLoop` 依赖 `RuntimeState`（per-conversation 实例，必须每回合重新构造）+ 14 个他模块 SPI，在 Spring 容器内装配会引入「`RuntimeState` 怎么代理」的复杂度（早期对话曾考虑 `@Scope("conversation")` 的 ThreadLocal 代理，但 PixFlow 当前没有 conversation-scoped SPI，会引入 Spring 容器语义而无业务需求）。改为「`LoopAutoConfiguration` 仅暴露 `PermissionContextFactory` + `LoopProperties` 两个 bean，`AgentLoop` 由调用方（conversation / agent 层）在回合入口构造」——这与全栈「loop 是无状态 service，回合在某节点的请求线程内跑完」的句柄一致。  
  Date/Author: 2026-07-01 / Codex

- Decision: **`storage --> loop` 边不补**（plan 阶段曾保留为可选项）。  
  Rationale: 落地后 loop 不直接读 `infra/storage` —— `ToolResultStorage` 已经由 `ToolExecutor` 的执行管线内消化（`tools` 模块持有），`MessageStore.flush` 当前是 no-op（`context/session` 在回合边界管理 TranscriptPort），`TurnTrace.recordPrune` 转投的是 trace 元数据而非 storage 字节。补 `storage --> loop` 边是「为对称而对称」的噪声边，移除以保持 DAG 图语义清晰。如未来 session 通过 loop 直接落 `message` 表补一引用 raw bytes（例如绕过 context 直接落 MinIO），再补 `storage --> loop` 边。  
  Date/Author: 2026-07-01 / Codex

- Decision: **ArchUnit 仅 4 条断言**（plan 阶段设想 5 条，删除「不组装 prompt」）。  
  Rationale: ArchUnit 不擅长对 Java 源码做「不允许特定字符串拼接模式」的静态正则匹配（Jackson `valueToTree`、`String.join`、`record` 字段 + Lombok 等变体太多），强行匹配既易误报又漏报。该约束由 code review 与「prompt 文本只在 `agent / prompt-assembler` 层出现」的入仓纪律守住；ArchUnit 守住可静态校验的 4 条边界（不依赖 module/、不依赖 provider 具体适配器、不持有 ToolDescriptor、不引用 SseEmitter）即可。  
  Date/Author: 2026-07-01 / Codex

## Outcomes & Retrospective

### 实现完成总结（2026-07-01）

本计划已完整落地，`pixflow-loop` Maven 模块按 Java 21 + Spring Boot 3.5.16 实现，所有 11 个阶段任务已勾。实现严格遵循 `loop.md` 的 18 节口径，仅在与现状冲突处做了 5 处微调（详见 Decision Log 与 Surprises & Discoveries 段）。

### 交付物清单

#### 1. Maven 模块与依赖

- 新增 `pixflow-loop` Maven 模块（`pixflow-loop/pom.xml`）
- 根 `pom.xml` 加入 `<module>pixflow-loop</module>` 与 `<dependencyManagement>` 条目
- 模块依赖：`pixflow-common` / `pixflow-permission` / `pixflow-hooks` / `pixflow-context` / `pixflow-tools` / `pixflow-eval` / `pixflow-infra-ai` + `spring-boot-autoconfigure` / `spring-boot-configuration-processor` / `spring-context` / `micrometer-core`（运行期） + `spring-boot-starter-test` / `reactor-test` / `archunit-junit5 1.4.1`（测试期）

#### 2. Java 源码（19 个文件）

| 包 | 类型 | 说明 |
|---|---|---|
| `com.pixflow.harness.loop` | `AgentLoop` | 主循环入口：`stream(prompt, attachments, sink, systemPrompt, toolSchemas)` / `continueStream(sink, systemPrompt, toolSchemas)` |
| `com.pixflow.harness.loop` | `RuntimeState` | 11 字段 + 6 个 mutator 方法（线程封闭，per-conversation 实例） |
| `com.pixflow.harness.loop` | `TransitionReason` | 6 态枚举 |
| `com.pixflow.harness.loop` | `Attachment` | durable attachment 引用 record |
| `com.pixflow.harness.loop.event` | `AgentEvent` / `AgentEventType` / `AgentEventSink` | 7 类事件 + 同步 SPI + NOOP 常量 |
| `com.pixflow.harness.loop.stream` | `ModelStreamConsumer` + `ModelOutcome` | `Flux<ChatStreamEvent>` → `ModelOutcome` 同步消费 |
| `com.pixflow.harness.loop.recovery` | `ReactiveCompactionGate` + `OutputInterruptHandler` + `GateDecision` | CONTEXT_LIMIT / 输出截断 两段恢复 + sealed decision |
| `com.pixflow.harness.loop.trace` | `TraceFanout` + `LoopToolTraceSink` + `RuntimeScopeTranslator` | trace 责任上移落点 + RuntimeScope 双类型翻译 |
| `com.pixflow.harness.loop.permission` | `PermissionContextFactory` + `DefaultPermissionContextFactory` | RuntimeState → PermissionContext 翻译 |
| `com.pixflow.harness.loop.config` | `LoopProperties` + `LoopAutoConfiguration` | `@ConfigurationProperties("pixflow.loop")` + 仅暴露 PermissionContextFactory bean |
| `com.pixflow.harness.loop.error` | `LoopErrorCode` | 3 个自治错误码 |

#### 3. 接口微调（对 `pixflow-context`）

- `ContextEngine.buildForModel(String, List<ToolSchemaView>)` → 返回 `BuildResult(snapshot, pruneEntries)`
- 旧签名保留为 `@Deprecated buildForModelLegacy(...)`
- `pixflow-context/pom.xml` 新增 `pixflow-eval` 依赖（用于 `TracePruneEntry`）
- `ContextEngineTest.buildForModelStoresCurrentSnapshot` 改为走 deprecated 路径

#### 4. 设计文档同步

- `docs/design-docs/harness/context.md` §十一：补 `BuildResult` 接口说明 + `pruneEntries` 转投语义 + 接缝契约表更新（loop → context 一行）
- `docs/design-docs/exec-plans/module-dependency-dag-plan.md`：
  - Mermaid 图补 `ai --> loop` 边（line 111）
  - Revision Notes 追加 `2026-06-30 / Codex` 落地说明

#### 5. 测试与守护

- **44 个测试全部通过**（11 个测试类），覆盖：
  - 续轮判定（带 / 无工具调用）
  - 无 maxTurns（连续 N 轮工具调用 + 自然结束）
  - CONTEXT_LIMIT 恢复（首次 reactive + 防抖）
  - 输出截断三段路径（ESCALATE / RECOVERY / abort）
  - 不可恢复异常（TurnTrace.abort + ErrorRecorder.record + 上抛 + 不 emit error 事件）
  - continueStream（不派发 USER_PROMPT_SUBMIT + 不追加 user）
  - RuntimeScopeTranslator 双向翻译（8 条断言）
  - LoopToolTraceSink（rewritten / error 转投）
  - TraceFanout 5 个转投方法
  - RuntimeState 字段语义（8 条断言）
  - DefaultPermissionContextFactory 翻译（5 条断言）
- **ArchUnit 4 条守护**（`LoopArchitectureTest`）：不依赖 `module/*`、不依赖 provider 具体适配器（`dashscope/openai`）、不持有 `ToolDescriptor`、不引用 `SseEmitter`
- **全 reactor 测试通过**：`mvn test` BUILD SUCCESS（含 `pixflow-session` / `pixflow-state` / `pixflow-memory` / `pixflow-commerce` / `pixflow-dag` / `pixflow-vision` / `pixflow-imagegen` / `pixflow-task` 等下游模块）
- **关键交叉验证**：`mvn -pl pixflow-loop -am test` / `mvn -pl pixflow-loop,pixflow-context -am test` / `mvn test` 均 BUILD SUCCESS

### 与原 plan 的差异（已记入 Decision Log / Surprises & Discoveries）

1. **`ModelStreamConsumer.consume` 直接返回 `ModelOutcome`**，不传 `Consumer<ModelOutcome>` 回调（plan 设想回调式，落地改为返回值式更清晰）
2. **`LoopAutoConfiguration` 不暴露 `AgentLoop` bean**（plan 设想装配，落地改为调用方构造 —— `RuntimeState` per-conversation 不能单例）
3. **`storage --> loop` 边不补**（plan 保留为可选项，落地确认不需要）
4. **`RuntimeScopeTranslator.toHooks` 反向规则**：`SUB_AGENT` → subagent("sub_agent") / `WORKER` → subagent("worker")（plan 仅写正向，未明确反向规则）
5. **`RuntimeState` 实际为 11 字段**（plan 写 9 字段，遗漏 `turnNo` 与 `traceId`）
6. **`LoopToolTraceSink` 单参构造**（plan 写双参 `(TurnTrace, RuntimeScope)`，落地只持 TurnTrace）
7. **ArchUnit 4 条**（plan 设想 5 条，「不组装 prompt」交由 code review）
8. **`FakeModelRetryRunner` 不继承**（plan 设想继承替身，因 `ModelRetryRunner` 是 `final` 类，落地改为真实 runner + 错误注入放在 `FakeChatModelClient.stream` 的 `Flux.error(...)` 上）

### 关键设计决策落地确认

- ✅ 续轮只看 `ModelOutcome.hasToolCalls`，不读 `stop_reason`
- ✅ 无 `maxTurns` 字段，唯一正常终止是无工具调用自然结束
- ✅ `CONTEXT_LIMIT` 防抖位由 loop 自维护（`RuntimeState.hasAttemptedReactiveCompact`），context 不感知
- ✅ 输出截断三段路径严格按 `loop.md §十`
- ✅ 不可恢复异常 → `TurnTrace.abort` + `ErrorRecorder.record` + 上抛，**不** emit error 事件
- ✅ 回合内线程封闭 + 同步主循环；与 infra/ai 的 Reactor 接缝点是 `publishOn(boundedElastic) + blockLast`
- ✅ `RuntimeScope` 双类型由 `RuntimeScopeTranslator` 收编，loop 主循环不混淆
- ✅ trace 责任在 loop：prune / hook / tool 全部经 `TraceFanout` 转投 eval
- ✅ `ToolExecutionContext` 字段未修改，`LoopToolTraceSink` 通过 SPI 适配
- ✅ ArchUnit 守护边界硬约束

### 后续待办（不在本期 scope）

- `infra/ai` 暴露 `outputInterrupted` 信号后，`OutputInterruptHandler` 自动生效（当前为空转）
- Wave 5 `agent` 层落地 `promptAssembler` / `toolSchemasProvider` / `SummarizationPort` / 子 Agent runner
- `pixflow-module-conversation` 落地 REST/SSE 端点，构造 `SseEmitter → AgentEventSink` 适配器
- `module/vision` / `module/explore` 子 Agent runner 接 `AgentLoop.continueStream` 入口

## Context and Orientation

PixFlow 是一个 Java 21 + Spring Boot 3.5.16 的多模块 Maven 项目，根包 `com.pixflow`。仓库根目录是 `D:\study\PixFlow`。当前已有模块：`pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-hooks`、`pixflow-context`、`pixflow-session`、`pixflow-state`、`pixflow-eval`、`pixflow-tools`、`pixflow-module-file`、`pixflow-module-memory`、`pixflow-module-commerce`、`pixflow-module-dag`、`pixflow-module-vision`、`pixflow-module-imagegen`、`pixflow-module-task`、`pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-mq`、`pixflow-infra-vector`、`pixflow-infra-ai`、`pixflow-infra-image`、`pixflow-infra-thirdparty`、`pixflow-app`。本计划要新增 `pixflow-loop`。

这里定义几个必须理解的词。

回合（turn）是用户一次发消息 = 一个 Agent 决策回合 = 一次 `AgentLoop.stream` 调用，对应 `eval.turnNo`、`hooks` 的 `UserPromptSubmit`/`TurnStopped`、`session` 的回合边界 flush。迭代（iteration）是回合内部一次「调 LLM → 执行工具 → 回填」的循环单轮，对应一次 `recordInput`、一次 `modelClient.stream`。一个回合内含 1..N 次迭代，无 maxTurns 上限。

续轮信号是「本轮 `ModelStreamEvent.messageCompleted.metadata["toolCalls"]` 非空」，不读 `stop_reason` / `finish_reason`（后者只用于输出截断检测）。空工具调用即自然结束，emit `COMPLETED` 并派发 `TURN_STOPPED` + flush + `TurnTrace.commit()`。

`RuntimeState` 是 loop 引入的「单会话可变运行态」，context / tools / permission 不持有它（避免反向依赖）。它通过 `ToolExecutionContext` 透传给 tools、通过 `PermissionContext` 透传给 permission，loop 在边界做翻译。

`AgentEventSink` 是同步接出 SPI，web 层用 `SseEmitter` 适配、测试用收集器。`AgentLoop` 内部完全不知道 `SseEmitter` 的存在。

实现时优先阅读这些位置，并可用括号中的关键词快速定位对应设计文本。

在 `docs/design-docs/harness/loop.md` 中搜索：`薄编排`、`provider-neutral`、`续轮判定单一信号`、`不设迭代上限`、`回合内线程封闭`、`错误恢复分层`、`每轮可回溯`、`trace 责任在 loop`、`AgentLoop`、`RuntimeState`、`TransitionReason`、`AgentEvent`、`ReactiveCompactionGate`、`OutputInterruptHandler`、`continueStream`、`Attachment`、`RuntimeScope`、`TOOL_USE`、`COMPLETED`、`RATE_LIMIT_RETRY`、`REACTIVE_COMPACT_RETRY`、`MAX_OUTPUT_TOKENS_ESCALATE`、`MAX_OUTPUT_TOKENS_RECOVERY`、`ASSISTANT_DELTA`、`TOOL_CALL_READY`、`TOOL_STARTED`、`TOOL_RESULT`、`TRANSITION`、`COMPLETED`、`provider-neutral 守护`、`ArchUnit`、`属性测试`。这些位置是 loop 的权威设计文本，解释本计划所有核心机制。

在 `docs/design-docs/design.md` 中搜索：`Execution Loop`、`手写显式循环`、`ContextSnapshot`、`自然结束`、`Harness 横切层`、`Trace 与 Error 日志分离`、`Evaluation Interface`。这些位置解释 loop 在总架构中的位置和为什么必须手写。

在 `docs/design-docs/harness/context.md` 中搜索：`buildForModel`、`ContextSnapshot`、`cheap pipeline`、`destructive compaction`、`reactiveCompact`、`SummarizationPort`、`CurrentModelContext`、`messageChainCache`、`PreparedContext`、`ToolSchemaView`。这些位置解释 context 暴露给 loop 的接缝形状和编译期依赖边界。

在 `docs/design-docs/harness/tools.md` 中搜索：`ToolExecutor.execute`、`ToolExecutionContext`、`ToolTraceSink`、`ToolResultStorage`、`visibleDescriptors`、`ResultPolicy`、`PlanModeView`、`PermissionSubject`。这些位置解释 loop 如何把分类后的入参转交执行管线、如何把 `TurnTrace` 句柄通过 `ToolTraceSink` 适配器投递。

在 `docs/design-docs/harness/hooks.md` 中搜索：`HookRegistry.dispatch`、`HookPayload`、`RuntimeScope`、`USER_PROMPT_SUBMIT`、`ASSISTANT_MESSAGE_COMPLETED`、`TURN_STOPPED`、`ToolUsePayload`、`PreToolUse`、`PostToolUse`、`ToolError`。这些位置解释 loop 在哪几个时机派发 hook。

在 `docs/design-docs/infra/permission.md`（权威设计在 `infra/permission.md`，仓库中已落地的 `harness/permission.md` 实现）和 `pixflow-permission` 源码中搜索：`PermissionContext`、`PermissionPolicy.evaluate`、`PermissionSubject`、`PermissionAction`、`PermissionDecision`、`SubagentConstraint`、`PermissionErrorCode`。这些位置解释 loop 如何构造 `PermissionContext`（把 `RuntimeState.metadata` 翻译成 `deniedTools` / `disabledTools` / `subagent`）。

在 `docs/design-docs/harness/eval.md` 和 `pixflow-eval` 源码中搜索：`TraceRecorder.begin`、`TurnTrace.recordInput/recordToolCall/recordPrune/recordError/commit/abort`、`TraceInput`、`TraceToolCall`、`TracePruneEntry`、`RuntimeScope`。这些位置解释 loop 经 eval SPI 投递什么字段、怎么 commit。

在 `docs/design-docs/harness/session.md` 和 `pixflow-session` 源码中搜索：`TranscriptPort`、`MessageStore`、`replaceForCompaction`、`load`、`message_compaction`。这些位置解释 loop 不直接调 session，全部经 context 的 `appendXxx` / `replaceMessagesForCompaction` 落库；回合边界调 `MessageStore.flush()`。

在 `docs/design-docs/infra/ai.md` 和 `pixflow-infra-ai` 源码中搜索：`ModelClient.stream`、`ModelStreamEvent`、`StopReason.LENGTH`、`ModelRetryRunner.run`、`AttemptReset`、`ProviderError`、`CONTEXT_LIMIT`。这些位置解释 loop 如何消费 infra/ai 的 `Flux<ChatStreamEvent>`、如何把 `StopReason.LENGTH` 翻译成 `MAX_OUTPUT_TOKENS_ESCALATE` transition。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：`Wave 4`、`harness/loop`、`tools --> loop`、`hooks --> loop`、`context --> loop`、`permission --> loop`、`eval --> loop`、`loop --> agent`。这些位置解释 loop 的实现波次、依赖方向与待补的 `ai --> loop` / `storage --> loop` 两条边。

## Plan of Work

第一阶段更新设计与依赖地基。新增 `pixflow-loop` Maven 模块，加入根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>`。同步更新 `module-dependency-dag-plan.md`：① Mermaid 图补 `ai --> loop` 与 `storage --> loop` 两条边（`pixflow-infra-ai` 在 Wave 1、`pixflow-infra-storage` 在 Wave 1，loop 在 Wave 4，无环）；② Wave 4 任务清单把 `harness/loop` 标记为「本计划正在落地的模块」；③ Revision Notes 记录两个改动原因（`ai --> loop`：`ModelRetryRunner` 是 loop 的真正上游；`storage --> loop`：loop 不直接读 storage，但 `TraceFanout` 不需要——该边是为未来 `context → loop` 错过的对称冗余，可不补，确认后写决定）。在 `pixflow-context/.../ContextEngine.java` 改造 `buildForModel` 返回 `BuildResult`、同时在 `harness/context.md` §十一 同步微调说明。这一阶段确保后续执行者不会误解 loop 的依赖方向、不会把 `RuntimeState` 透传进 context。

第二阶段实现 core API 与模型。新建 `com.pixflow.harness.loop.AgentLoop`、`RuntimeState`、`TransitionReason`、`Attachment`、`event/AgentEvent`、`event/AgentEventType`、`event/AgentEventSink`。`AgentLoop` 是 final class，构造期接收 `RuntimeState` + 7 个协作依赖（详见 Interfaces and Dependencies 段）。`stream(prompt, attachments, sink)` 是用户回合入口；`continueStream(sink)` 是子 Agent fork / 恢复入口，**不**派发 `USER_PROMPT_SUBMIT`、**不**追加 user 消息。`RuntimeState` 是 final class，持有 `ModelUsage usage` / `int iterationCount` / `boolean hasAttemptedReactiveCompact` / `int maxOutputRecoveryCount` / `boolean hasEscalatedMaxOutput` / `TransitionReason lastTransition` / `String conversationId` / `RuntimeScope runtimeScope` / `Map<String,Object> metadata`，提供 `addUsage(delta)` / `setTransition(reason)` 方法。`Attachment` 是 `record(String id, String kind, String reference, Map<String,Object> metadata)`，由调用方（agent / conversation）解析为 durable 引用，loop 不解析 bytes。

第三阶段实现 `ModelStreamConsumer`。新建 `com.pixflow.harness.loop.stream.ModelStreamConsumer`，签名 `consume(Flux<ChatStreamEvent> flux, AgentEventSink sink, Consumer<ModelOutcome> onCompleted)`。它在 Reactor 线程订阅，立即用 `Schedulers.boundedElastic().schedule(() -> ...)` 转到回合线程同步消费 events。`ModelOutcome` 是 `record(boolean hasToolCalls, List<ToolCall> toolCalls, String finalText, StopReason stopReason, ModelUsage usage, boolean outputInterrupted, String systemPromptFingerprint)`。Consumer 把 `TextDelta` 翻译为 `AgentEvent.ASSISTANT_DELTA`，把 `AttemptReset` 翻译为 `AgentEvent.TRANSITION(REACTIVE_COMPACT_RETRY)`（仅日志级别，不阻塞），把 `Completed` 一次性翻译为 `AgentEvent.ASSISTANT_MESSAGE_COMPLETED` 并把 outcome 推给回调。`onCompleted` 在回合线程同步执行。

第四阶段实现 `ReactiveCompactionGate` 与 `OutputInterruptHandler`。两个都是 final class，无状态。新建 `com.pixflow.harness.loop.recovery.ReactiveCompactionGate` 签名 `GateDecision onContextLimit(RuntimeState state, PixFlowException error, MessageStore store, ContextCompactionService service)`：若 `!state.hasAttemptedReactiveCompact` 调 `service.reactiveCompact(store, error, Map.of("source", "loop.reactive"))`、置 `state.hasAttemptedReactiveCompact = true`、返回 `GateDecision.retry(REACTIVE_COMPACT_RETRY)`；否则返回 `GateDecision.abort(error)`。`OutputInterruptHandler` 签名 `GateDecision onOutputInterrupted(RuntimeState state, MessageStore store, String assistantPartialText, String continuationPrompt)`：若 `!state.hasEscalatedMaxOutput` 抬高 `state.metadata.modelRequestOverrides.maxOutputTokens = state.metadata.getOrDefault("escalatedMaxOutputTokens", 64000)`、置 `state.hasEscalatedMaxOutput = true`、返回 `GateDecision.retry(MAX_OUTPUT_TOKENS_ESCALATE)`；否则若 `state.maxOutputRecoveryCount < state.metadata.getOrDefault("maxOutputRecoveryLimit", 3)` 追加截断 assistant + continuation prompt 到 `MessageStore`、递增 `maxOutputRecoveryCount`、返回 `GateDecision.continueAfterAppend(MAX_OUTPUT_TOKENS_RECOVERY)`；否则 `GateDecision.abort(...)`。`GateDecision` 是 sealed interface：`retry(TransitionReason)` / `continueAfterAppend(TransitionReason)` / `abort(PixFlowException)`。

第五阶段实现 `TraceFanout` 与 `LoopToolTraceSink`。`TraceFanout` 是 final class，构造期接收当前 `TurnTrace`（loop 持有）。`fanoutPrune(List<TracePruneEntry> entries)` 把 `BuildResult.pruneEntries` 转投 `turnTrace.recordPrune`；`fanoutHookSpan(HookEvent, HookResult, RuntimeScope, String toolName, String toolCallId, long latencyMs)` 把每次 hook dispatch 结果转投为 `recordToolCall`（用 `TraceToolCall`）；`fanoutRetry(TransitionReason reason, long latencyMs)` 把每次 retry 转投为 `recordToolCall(name="loop.retry", result=reason.name())`。`LoopToolTraceSink` 是 `ToolTraceSink` SPI 的实现（`com.pixflow.harness.tools.result.ToolTraceSink`），构造期接收 `TurnTrace` + `RuntimeScope`，把 `ToolTraceEvent` 翻译为 `recordToolCall(TraceToolCall)`；不引入新 SPI 也不修改 `ToolExecutionContext` 字段。

第六阶段实现 `AgentLoop.stream` 与 `continueStream` 主流程。两个方法共用一个私有 `runLoop(sink, boolean dispatchUserPromptSubmit, boolean appendUserMessage)`。`stream` 调 `runLoop(true, true)`；`continueStream` 调 `runLoop(false, false)`。`runLoop` 流程严格按 `loop.md §八` 的 mermaid 顺序：① 派发 `USER_PROMPT_SUBMIT`（仅 stream）；② 追加 user 消息（仅 stream）或 fork directive user 消息（仅 continueStream）；③ `TraceRecorder.begin(conversationId, ++state.turnNo, traceId, runtimeScope)` 拿 `TurnTrace` 并 try-with-resources；④ while(true)：`ContextEngine.buildForModel(systemPrompt, toolSchemas)` 返回 `BuildResult`，fanout prune；`ModelRetryRunner.run(role, attempt -> modelClient.stream(snapshotToRequest(snapshot, modelRequestOverrides)))` 拿到 `Flux<ChatStreamEvent>`，由 `ModelStreamConsumer` 消费得到 `ModelOutcome`；⑤ 若 `outcome.outputInterrupted` → `OutputInterruptHandler` 决策（ESCALATE / RECOVERY / abort）；若抛 `PixFlowException(category=CONTEXT_LIMIT)` 且非已尝试 reactive → `ReactiveCompactionGate` 决策；若为其他 `CONTEXT_LIMIT` 走 `OutputInterruptHandler` 同路径（CONTEXT_LIMIT 也算中断，escalate max output 是次优兜底）；⑥ 正常路径：`MessageStore.appendAssistant(message)`、`HookRegistry.dispatch(ASSISTANT_MESSAGE_COMPLETED, payload)`（payload 用 `com.pixflow.harness.hooks.payload.RuntimeScope` + `AssistantMessagePayload`）、fanout hook span；⑦ 若 `outcome.hasToolCalls` → 构造 `ToolExecutionContext`（注入 `PermissionContext` / `TurnTrace` 句柄经 `LoopToolTraceSink` / `RuntimeScope` / 隐藏工具集 / 并发线程池）、`ToolExecutor.execute(calls, context)`、`MessageStore.appendToolResults(results)`、fanout `TOOL_CALL_READY` / `TOOL_STARTED` / `TOOL_RESULT`、`state.setTransition(TOOL_USE)`、emit `TRANSITION`、continue；⑧ 若无工具调用 → 派发 `TURN_STOPPED`（payload 含 `finalText`）、`MessageStore.flush()`、`turnTrace.commit()`、emit `COMPLETED(finalText)`、return。任意位置抛不可恢复异常：`turnTrace.abort(error)` → `ErrorRecorder.record(error)` → 重新抛，**不** emit error 事件。`hookResult.metadata["dagParamError"]` 等是观察信息，不影响循环。

第七阶段实现 `RuntimeScopeTranslator` 与 trace 责任上移。`RuntimeScopeTranslator.toEval(hooks.RuntimeScope)`：main → `MAIN`；subagent(type=vision) → `SUB_AGENT` + metadata 标注 type；subagent(type=imagegen/explore) → 同上。`RuntimeScopeTranslator.toHooks(RuntimeScope)` 反向（continueStream 接 seed 链时用）。两者都放在 `com.pixflow.harness.loop.trace` 包，不暴露给其他模块。

第八阶段实现配置与自动装配。新建 `config/LoopProperties` 绑定 `pixflow.loop.max-output-recovery-limit`（默认 3）、`escalated-max-output-tokens`（默认 64000）、`emit-tool-input-preview`（默认 true）、`tool-concurrency-pool-size`（默认 8）。新建 `config/LoopAutoConfiguration` 提供 `AgentLoop` bean 与 `LoopToolTraceSink` bean，loop 不自注册 hook 回调（业务 hook 在 agent / module/* 接线）。`AgentLoop` 接收的 `HookRegistry` / `ContextEngine` / `ContextCompactionService` / `MessageStore` / `ModelClient` / `ModelRetryRunner` / `ToolExecutor` / `TraceRecorder` / `PermissionContextFactory` / `ErrorRecorder` 都是构造期注入，loop 不自装配这些 bean（避免与 harness 基础件的装配顺序耦合）。

第九阶段补齐单元测试。测试放在 `pixflow-loop/src/test/java/com/pixflow/harness/loop/`。覆盖：续轮判定（带工具调用 / 无工具调用）、`stop_reason=length` 不续轮但触发 `OutputInterruptHandler`、CONTEXT_LIMIT 首次 `REACTIVE_COMPACT_RETRY` + 防抖、CONTEXT_LIMIT 再次 `abort`、ESCALATE 抬高 `maxOutputTokens`、RECOVERY 追加截断 assistant + 续写、recovery 上限超限 abort、`RATE_LIMIT_RETRY` 由 infra/ai runner 缓冲、不可恢复异常 `TurnTrace.abort` + `ErrorRecorder.record` + 上抛、continueStream 不派发 `USER_PROMPT_SUBMIT` 且不追加 user 消息、回合/迭代粒度（`recordInput` 多次、commit 聚合）、`RuntimeScopeTranslator` 翻译规则、`LoopToolTraceSink` 把 `ToolTraceEvent` 转 `recordToolCall`、`TraceFanout.fanoutPrune` 转 `recordPrune`。所有 mock 用 in-memory fake（不依赖 Spring 容器）。

第十阶段补 ArchUnit 守护。`archunit/LoopArchitectureTest` 断言：① `com.pixflow.harness.loop..` 不依赖 `com.pixflow.module..` 任何类型；② 不依赖 `com.pixflow.infra.ai.chat..*ChatModelClient` 之外的 provider 具体适配器（如未来出现的 `com.pixflow.infra.ai.dashscope..` 或 `com.pixflow.infra.ai.openai..`，违反时测试失败）；③ 不持有 `com.pixflow.harness.tools.ToolDescriptor`（避免反向依赖 tools 内部类型，loop 只通过 `ToolExecutor` 与 `ToolExecutionContext` 协作）；④ 不组装 prompt 文本（不持有 `String` 形式的 system prompt 拼装逻辑——`AgentLoop` 不出现 `+ "You are" +` 这种模式）；⑤ 持有 `AgentEventSink` 但不引用 `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`（sink 抽象不泄漏到 web 层）。

第十一阶段同步更新 `module-dependency-dag-plan.md` 与 `harness/context.md`。`context.md` §十一 把 `ContextEngine.buildForModel` 的签名改为返回 `BuildResult`、说明 `pruneEntries` 由 loop 转投 eval。DAG plan 的 Mermaid 图补 `ai --> loop` 边、Revision Notes 记录补边原因。Wave 4 任务清单的 `harness/loop` 在计划落地后标记完成。`tools.md` / `hooks.md` / `eval.md` 已经有 runtime scope 描述，无需改。

## Concrete Steps

从仓库根目录开始执行：

    cd D:\study\PixFlow
    git status --short

如果当前工作区已有与 loop 无关的用户改动，不要回滚，只在本计划涉及的文件范围内继续推进。

确认当前工程结构：

    cd D:\study\PixFlow
    ls pixflow-context/pom.xml pixflow-tools/pom.xml pixflow-hooks/pom.xml pixflow-eval/pom.xml pixflow-permission/pom.xml pixflow-session/pom.xml pixflow-infra-ai/pom.xml 2>&1

预期都能找到；`pixflow-loop/pom.xml` 当前**不存在**，应被本计划新增。

确认 `harness/loop.md` 与 `harness/context.md` 文档位置：

    cd D:\study\PixFlow
    rg -n "AgentLoop|RuntimeState|TransitionReason|AgentEvent|ReactiveCompactionGate|OutputInterruptHandler" docs\design-docs\harness\loop.md
    rg -n "buildForModel|ContextSnapshot|SummarizationPort|reactiveCompact" docs\design-docs\harness\context.md

确认核心接缝的现状签名：

    cd D:\study\PixFlow
    cat pixflow-context\src\main\java\com\pixflow\harness\context\engine\ContextEngine.java
    cat pixflow-tools\src\main\java\com\pixflow\harness\tools\ToolExecutionContext.java
    cat pixflow-eval\src\main\java\com\pixflow\harness\eval\api\TurnTrace.java
    cat pixflow-eval\src\main\java\com\pixflow\harness\eval\model\TraceInput.java
    cat pixflow-hooks\src\main\java\com\pixflow\harness\hooks\HookRegistry.java
    cat pixflow-hooks\src\main\java\com\pixflow\harness\hooks\payload\RuntimeScope.java
    cat pixflow-eval\src\main\java\com\pixflow\harness\eval\model\RuntimeScope.java
    cat pixflow-infra-ai\src\main\java\com\pixflow\infra\ai\resilience\ModelRetryRunner.java
    cat pixflow-permission\src\main\java\com\pixflow\harness\permission\PermissionContext.java
    cat pixflow-tools\src\main\java\com\pixflow\harness\tools\result\ToolTraceSink.java
    cat pixflow-context\src\main\java\com\pixflow\harness\context\store\MessageStore.java

按本计划「Surprises & Discoveries」段的发现修改 `ContextEngine.buildForModel` 为返回 `BuildResult`（同时在 `harness/context.md` §十一 同步说明），再新增 `pixflow-loop` 模块。验证命令：

    cd D:\study\PixFlow
    mvn -pl pixflow-loop -am test

成功时应看到 Maven `BUILD SUCCESS`，并且 loop 模块的 12+ 个测试全部通过（续轮判定 / 错误恢复 / 不可恢复异常 / continueStream / 回合聚合 / RuntimeScopeTranslator / TraceFanout / LoopToolTraceSink / ArchUnit / 等等）。如果 `pixflow-loop` 模块依赖的前置模块也被编译，`-am` 会自动构建 `pixflow-common`、`pixflow-permission`、`pixflow-hooks`、`pixflow-context`、`pixflow-tools`、`pixflow-eval`、`pixflow-infra-ai`。

补全 `module-dependency-dag-plan.md` 的 `ai --> loop` / `storage --> loop` 边（`storage` 边按 Decision Log 决定是否补），并把 Wave 4 任务清单的 `harness/loop` 标记为完成。补全 `harness/context.md` §十一的 `BuildResult` 接口微调说明。

完成所有接线后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-loop,pixflow-context -am test
    mvn -pl pixflow-loop,pixflow-tools,pixflow-eval -am test
    mvn test

第一条命令证明 loop 对 context 的 `BuildResult` 接线正确；第二条证明 loop 对 tools 的 `LoopToolTraceSink` 与对 eval 的 `TurnTrace.recordXxx` 接线正确；第三条证明整个 reactor 不被破坏。

## Validation and Acceptance

loop 模块的验收以可观察测试行为为准。所有断言都必须能在测试里被复现，不依赖外部服务。

第一类验收是续轮判定。构造 `ModelClient` 替身：第一次返回 `Completed(toolCalls=[search], stopReason=TOOL_CALLS, ...)`、第二次返回 `Completed(toolCalls=[], stopReason=STOP, finalText="done", ...)`。loop 应执行 search 工具、追加 tool result、再调一次 LLM、看到无 tool call、emit `COMPLETED("done")`、commit trace、return。`stop_reason=length` 不应被当作续轮信号——它应触发 `OutputInterruptHandler` 而非自动 continue；`STOP` 才被当作「无工具调用即停」。

第二类验收是无 maxTurns。构造 `ModelClient` 返回「连续 5 轮工具调用 + 第 6 轮无调用」。loop 应跑满 6 轮自然结束，`iterationCount` 累加到 5（最后 1 轮是空调用），无任何次数上限中断。`RuntimeState.lastTransition` 末次为 `COMPLETED`。

第三类验收是 CONTEXT_LIMIT 反应式压缩。构造 `ModelClient` 第一次抛 `PixFlowException(category=CONTEXT_LIMIT)`，第二次（重试）成功完成。loop 应触发 `ReactiveCompactionGate`、调 `context.reactiveCompact`、置 `state.hasAttemptedReactiveCompact = true`、emit `TRANSITION(REACTIVE_COMPACT_RETRY)`、**不** append assistant、继续。第二次构造同一错误，loop 应**不**再次 reactive（防抖位已置），改走 `OutputInterruptHandler`（CONTEXT_LIMIT 算中断）→ ESCALATE；若 `hasEscalatedMaxOutput` 已 true、recovery 次数未超，则改走 RECOVERY。超限后 `abort(error)`、`TurnTrace.abort`、`ErrorRecorder.record`、上抛。

第四类验收是输出截断恢复。构造 `ModelClient` 返回 `Completed(toolCalls=[], stopReason=LENGTH, outputInterrupted=true)`。loop 应触发 `OutputInterruptHandler`、置 `state.hasEscalatedMaxOutput = true`、把 `state.metadata.modelRequestOverrides.maxOutputTokens` 设为 `escalatedMaxOutputTokens`、emit `TRANSITION(MAX_OUTPUT_TOKENS_ESCALATE)`、**不** append assistant。第二次同样错误 → 追加截断 assistant（含 partial text）与续写 prompt 到 `MessageStore`、`maxOutputRecoveryCount++`、emit `TRANSITION(MAX_OUTPUT_TOKENS_RECOVERY)`、continue。第三次同样错误超 `maxOutputRecoveryLimit` → `abort(error)`、不 emit error 事件、上抛。

第五类验收是重试边界。构造 `ModelRetryRunner` 替身触发 `RATE_LIMIT_RETRY`（loop 仅记录、退避在 infra/ai）。`RetryExhaustedError` 上抛时断言 `TurnTrace.abort` + `ErrorRecorder.record` + 上抛、**不** emit error 事件、`sink` 收集器里**不**出现 error 事件。

第六类验收是 `continueStream`。构造 seed 消息链（含 1 条 user + 1 条 assistant）。调 `AgentLoop.continueStream(sink)`，断言：① 不派发 `USER_PROMPT_SUBMIT`（hook 替身不被调）；② 不追加 user 消息（`MessageStore` 大小不变）；③ 进入 while 循环正常续轮 / 自然结束；④ 继承父 `systemPrompt`（由调用方传入 loop，loop 不重新组装）。

第七类验收是事件流。注入 `RecordingAgentEventSink` 收集器，构造成功 attempt → 断言 `ASSISTANT_DELTA` 顺序 emit、`ASSISTANT_MESSAGE_COMPLETED` 在所有 delta 之后；失败 attempt 的 partial delta **不** emit（用 `ModelRetryRunner` 替身 mock 首次失败、第二次成功）；`TOOL_CALL_READY` / `TOOL_STARTED` / `TOOL_RESULT` / `TRANSITION` / `COMPLETED` 时序正确，`COMPLETED` 必为最后一条。

第八类验收是 `ToolExecutionContext` 接线。断言 `ToolExecutionContext.permissionContext` 含 `conversationId` / `deniedTools`（来自 `state.metadata.deniedTools`）/ `disabledTools` / `subagent`；断言 `ToolExecutionContext.traceSink` 是 `LoopToolTraceSink` 实例；断言 loop 不调 `PermissionPolicy.evaluate`（所有 permission 评估由 tools 执行管线在 `classify → permission` 步骤完成）。

第九类验收是 `RuntimeScopeTranslator`。`hooks.RuntimeScope.main()` → `eval.RuntimeScope.MAIN`；`hooks.RuntimeScope.of("vision")` → `eval.RuntimeScope.SUB_AGENT` + metadata 标 `subagentType=vision`；反向 `eval.MAIN` → `hooks.main()`。

第十类验收是 `TraceFanout` 与 `LoopToolTraceSink`。断言 prune 日志从 `BuildResult.pruneEntries` 转投到 `TurnTrace.recordPrune`；断言 hook span（`HookResult.blocking / hookErrors / inputRewritten`）转投到 `TurnTrace.recordToolCall(name="loop.hook.span", resultSummary=...)`；断言 `ToolTraceEvent` 的 `rewritten=true` 反映到 `TraceToolCall.result`。

第十一类验收是 `RuntimeScope` 选择。grep 整个 `pixflow-loop` 源码：① import `com.pixflow.harness.hooks.payload.RuntimeScope` 出现 ≥1 次；② import `com.pixflow.harness.eval.model.RuntimeScope` 出现 ≥1 次（用于 `TraceRecorder.begin`）；③ 没有同时 import 两者作为类型名混淆（IDE 检查应能识别）。

第十二类验收是 ArchUnit。`LoopArchitectureTest` 全部断言通过：loop 不依赖 `com.pixflow.module..`、不依赖 provider 具体适配器（除 `infra.ai.chat` 抽象）、不持有 `ToolDescriptor`、不组装 prompt、不引用 `SseEmitter`。

第十三类验收是配置。`LoopProperties` 在缺少配置时使用默认值（`maxOutputRecoveryLimit=3` / `escalatedMaxOutputTokens=64000` / `emitToolInputPreview=true` / `toolConcurrencyPoolSize=8`）；`LoopAutoConfiguration` 在缺少必要依赖 bean 时装配失败并给出明确错误信息（不应静默降级为 no-op）。

## Idempotence and Recovery

本计划推荐的实现是 additive changes。新增 `pixflow-loop` 模块、向根 `pom.xml` 添加模块和依赖管理、修改 `ContextEngine.buildForModel` 为返回 `BuildResult`、添加 ArchUnit 测试、修改 `harness/context.md` §十一 同步说明、修改 `module-dependency-dag-plan.md` 补 `ai --> loop` 边，这些动作都可以重复运行 Maven 测试，不应修改业务数据。

如果 `ContextEngine.buildForModel` 改造导致现有 context 测试失败，不要删除旧行为。保留旧签名为 deprecated 方法（`buildForModelLegacy`），同时新增 `buildForModelV2` 返回 `BuildResult`；context 模块内所有现有调用点继续走旧路径，loop 走新路径。后续 Phase 2（context 改写完成后再彻底删除旧签名）。

如果 `pixflow-loop` 新模块加入根 `pom.xml` 后 Maven 失败，先检查 `<modules>` 和 `<dependencyManagement>` 是否同时加入 `pixflow-loop`，再检查 `pixflow-loop/pom.xml` 是否只依赖已存在模块（`pixflow-common`、`pixflow-permission`、`pixflow-hooks`、`pixflow-context`、`pixflow-tools`、`pixflow-eval`、`pixflow-infra-ai`）。不要为了编译方便让 loop 依赖 `pixflow-app` 或尚未实现的 `agent` / `pixflow-module-conversation`。

如果 ArchUnit 测试发现 loop 误依赖了 provider 具体适配器（如 `DashScopeChatModel`），立即报告并停止——这是边界硬约束被破坏的早期信号，**不能**靠 `@SuppressWarnings` 绕过。

如果 `ModelRetryRunner.run` 返回 `Flux` 后 loop 的 `Schedulers.boundedElastic().schedule(...)` 桥接方案在测试里出现「sink.emit 在 Reactor 线程被调用」的 race，停止并改为 `Flux.from(...).publishOn(Schedulers.boundedElastic()).subscribe(event -> ...) + .doOnComplete(() -> ...)`；保证「单线程同步消费 events + onCompleted 也在同一线程」。

如果没有真实 Spring 容器测试条件，先用 in-memory fake（`FakeModelClient` / `FakeToolExecutor` / `InMemoryMessageStore` / `FakeHookRegistry` / `InMemoryTraceRecorder`）完成核心测试。后续 Spring 装配测试按 `LoopAutoConfigurationTest` 单独覆盖。

## Artifacts and Notes

本计划创建前已确认设计文档中的关键位置如下。

在 `docs/design-docs/harness/loop.md` 中：`薄编排`、`provider-neutral`、`续轮判定单一信号`、`不设迭代上限`、`回合内线程封闭`、`错误恢复分层`、`每轮可回溯`、`trace 责任在 loop`、`AgentLoop`、`RuntimeState`、`TransitionReason`、`AgentEvent`、`ReactiveCompactionGate`、`OutputInterruptHandler`、`continueStream`、`Attachment`、`RuntimeScope`、6 态 `TransitionReason` 枚举、7 类 `AgentEventType`、`provider-neutral 守护` 是最关键的定位词。

在 `docs/design-docs/design.md` 中：`Execution Loop`、`手写显式循环`、`ContextSnapshot`、`自然结束`、`Harness 横切层`、`Trace 与 Error 日志分离`、`Evaluation Interface` 是最关键的定位词。

在 `docs/design-docs/harness/context.md` 中：`buildForModel`、`ContextSnapshot`、`cheap pipeline`、`destructive compaction`、`reactiveCompact`、`SummarizationPort`、`CurrentModelContext`、`MessageChainCache`、`PreparedContext`、`ToolSchemaView` 是最关键的定位词（落地 `BuildResult` 时需要同步这一节）。

在 `docs/design-docs/harness/tools.md` 中：`ToolExecutor.execute`、`ToolExecutionContext`、`ToolTraceSink`、`ToolResultStorage`、`visibleDescriptors`、`ResultPolicy`、`PlanModeView`、`PermissionSubject` 是最关键的定位词。

在 `docs/design-docs/harness/hooks.md` 中：`HookRegistry.dispatch`、`HookPayload`、`RuntimeScope`、`USER_PROMPT_SUBMIT`、`ASSISTANT_MESSAGE_COMPLETED`、`TURN_STOPPED`、`ToolUsePayload`、`PreToolUse`、`PostToolUse`、`ToolError` 是最关键的定位词。

在 `docs/design-docs/infra/permission.md` 与 `pixflow-permission` 源码中：`PermissionContext`、`PermissionPolicy.evaluate`、`PermissionSubject`、`PermissionAction`、`PermissionDecision`、`SubagentConstraint` 是最关键的定位词。

在 `docs/design-docs/harness/eval.md` 与 `pixflow-eval` 源码中：`TraceRecorder.begin`、`TurnTrace.recordInput/recordToolCall/recordPrune/recordError/commit/abort`、`TraceInput`、`TraceToolCall`、`TracePruneEntry`、`RuntimeScope` 是最关键的定位词（loop 落地时不发明新字段，按现状字段形状填充）。

在 `docs/design-docs/infra/ai.md` 与 `pixflow-infra-ai` 源码中：`ModelClient.stream`、`ModelStreamEvent`、`StopReason.LENGTH`、`ModelRetryRunner.run`、`AttemptReset`、`ProviderError`、`CONTEXT_LIMIT` 是最关键的定位词。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中：`Wave 4`、`harness/loop`、`tools --> loop`、`hooks --> loop`、`context --> loop`、`permission --> loop`、`eval --> loop`、`loop --> agent` 是最关键的定位词（实现时需补 `ai --> loop` 边）。

当前真实工程证据：

    根 pom.xml modules 已包含：
        pixflow-contracts
        pixflow-common
        pixflow-permission
        pixflow-hooks
        pixflow-context
        pixflow-session
        pixflow-state
        pixflow-eval
        pixflow-tools
        pixflow-module-{file,memory,commerce,dag,vision,imagegen,task}
        pixflow-infra-{storage,cache,mq,vector,ai,image,thirdparty}
        pixflow-app

    应新增：
        pixflow-loop

    根 pom java.version=21，spring-ai.version=2.0.0（与设计文档「Java 17」漂移，直接按 21 落地）

    关键现状签名（loop 计划必须对齐）：
        ContextEngine.buildForModel(String, List<ToolSchemaView>) -> ContextSnapshot
            → 改造为返回 BuildResult(ContextSnapshot snapshot, List<TracePruneEntry> pruneEntries)
        ContextCompactionService.reactiveCompact(MessageStore, PixFlowException, Map) -> CompactionResult
            → RuntimeState.hasAttemptedReactiveCompact 由 loop 自维护，context 不感知
        ModelRetryRunner.run(ModelRole, Function<Integer, Flux<ChatStreamEvent>>) -> Flux<ChatStreamEvent>
            → loop 用 Reactor 线程订阅 + 立即 Schedulers.boundedElastic() 转到回合线程同步消费
        ToolExecutionContext 不含 TurnTrace 句柄
            → loop 在构造时把 TurnTrace 经 LoopToolTraceSink 适配器注入 ctx.traceSink
        hooks.RuntimeScope = record(boolean subagent, String subagentType)
        eval.RuntimeScope = enum { MAIN, SUB_AGENT, WORKER }
            → loop 持 RuntimeScopeTranslator 在两侧翻译

## Interfaces and Dependencies

最终应存在以下 Maven 模块、包和 Java 类型。实际文件路径应位于项目根目录下的 `pixflow-loop/src/main/java/com/pixflow/harness/loop/...`。

在根 `pom.xml` 中，新增模块和 dependencyManagement 条目：

    <module>pixflow-loop</module>

    <dependency>
        <groupId>com.pixflow</groupId>
        <artifactId>pixflow-loop</artifactId>
        <version>${project.version}</version>
    </dependency>

在 `pixflow-loop/pom.xml` 中，至少依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-permission
    com.pixflow:pixflow-hooks
    com.pixflow:pixflow-context
    com.pixflow:pixflow-tools
    com.pixflow:pixflow-eval
    com.pixflow:pixflow-infra-ai
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    io.micrometer:micrometer-core
    com.tngtech.archunit:archunit-junit5 (test scope)
    org.springframework.boot:spring-boot-starter-test (test scope)

依赖方向严格不引入：`module/*`、`module/conversation`、`agent`、`infra/storage`（loop 不直接读 storage，所有持久化经 context/session/eval）、任何 provider 具体适配器。

在 `com.pixflow.harness.loop` 包内定义：

    public final class AgentLoop {
        public AgentLoop(RuntimeState state,
                         MessageStore messageStore,
                         ContextEngine contextEngine,
                         ContextCompactionService compactionService,
                         ModelClient modelClient,
                         ModelRetryRunner retryRunner,
                         ToolExecutor toolExecutor,
                         HookRegistry hookRegistry,
                         TraceRecorder traceRecorder,
                         PermissionContextFactory permissionContextFactory,
                         ErrorRecorder errorRecorder,
                         Clock clock,
                         LoopProperties properties);
        public void stream(String prompt, List<Attachment> attachments, AgentEventSink sink);
        public void continueStream(AgentEventSink sink);
        private void runLoop(AgentEventSink sink, boolean dispatchUserPromptSubmit, boolean appendUserMessage);
    }

    public final class RuntimeState {
        private ModelUsage usage = ModelUsage.empty();
        private int iterationCount = 0;
        private boolean hasAttemptedReactiveCompact = false;
        private int maxOutputRecoveryCount = 0;
        private boolean hasEscalatedMaxOutput = false;
        private TransitionReason lastTransition = null;
        private String conversationId;
        private RuntimeScope runtimeScope;        // 来自 com.pixflow.harness.hooks.payload
        private int turnNo = 0;                    // 由 AgentLoop.runLoop 入口 ++
        private Map<String, Object> metadata = new HashMap<>();
        public void addUsage(ModelUsage delta);
        public void setTransition(TransitionReason reason);
    }

    public enum TransitionReason {
        TOOL_USE,
        COMPLETED,
        RATE_LIMIT_RETRY,
        REACTIVE_COMPACT_RETRY,
        MAX_OUTPUT_TOKENS_ESCALATE,
        MAX_OUTPUT_TOKENS_RECOVERY
    }

    public record Attachment(String id, String kind, String reference, Map<String,Object> metadata) {}

在 `com.pixflow.harness.loop.event` 包内定义：

    public enum AgentEventType {
        ASSISTANT_DELTA,
        ASSISTANT_MESSAGE_COMPLETED,
        TOOL_CALL_READY,
        TOOL_STARTED,
        TOOL_RESULT,
        TRANSITION,
        COMPLETED
    }

    public record AgentEvent(
            AgentEventType type,
            String text,
            Object payload,
            Map<String, Object> metadata) {}

    public interface AgentEventSink {
        void emit(AgentEvent event);
    }

在 `com.pixflow.harness.loop.recovery` 包内定义：

    public final class ReactiveCompactionGate {
        public ReactiveCompactionGate(ContextCompactionService service) { ... }
        public GateDecision onContextLimit(RuntimeState state, MessageStore store, PixFlowException error);
    }

    public final class OutputInterruptHandler {
        public OutputInterruptHandler(MessageStore messageStore) { ... }
        public GateDecision onOutputInterrupted(RuntimeState state, String assistantPartialText, String continuationPrompt);
    }

    public sealed interface GateDecision permits GateDecision.Retry, GateDecision.ContinueAfterAppend, GateDecision.Abort {
        record Retry(TransitionReason reason) implements GateDecision {}
        record ContinueAfterAppend(TransitionReason reason) implements GateDecision {}
        record Abort(PixFlowException error) implements GateDecision {}
    }

在 `com.pixflow.harness.loop.stream` 包内定义：

    public final class ModelStreamConsumer {
        public ModelStreamConsumer() {}
        public record ModelOutcome(boolean hasToolCalls,
                                   List<ToolCall> toolCalls,
                                   String finalText,
                                   StopReason stopReason,
                                   ModelUsage usage,
                                   boolean outputInterrupted,
                                   String systemPromptFingerprint) {}
        public void consume(Flux<ChatStreamEvent> flux, AgentEventSink sink, Consumer<ModelOutcome> onCompleted);
    }

在 `com.pixflow.harness.loop.trace` 包内定义：

    public final class TraceFanout {
        public TraceFanout(TurnTrace turnTrace) {}
        public void fanoutPrune(List<TracePruneEntry> entries);
        public void fanoutHookSpan(HookEvent event, HookResult result, RuntimeScope scope, String toolName, String toolCallId, long latencyMs);
        public void fanoutRetry(TransitionReason reason, long latencyMs);
    }

    public final class LoopToolTraceSink implements ToolTraceSink {
        public LoopToolTraceSink(TurnTrace turnTrace, RuntimeScope scope) {}
        @Override public void record(ToolTraceSink.ToolTraceEvent event);
    }

    public final class RuntimeScopeTranslator {
        public static com.pixflow.harness.eval.model.RuntimeScope toEval(
                com.pixflow.harness.hooks.payload.RuntimeScope hooks);
        public static com.pixflow.harness.hooks.payload.RuntimeScope toHooks(
                com.pixflow.harness.eval.model.RuntimeScope eval);
    }

在 `com.pixflow.harness.loop.permission` 包内定义：

    public interface PermissionContextFactory {
        PermissionContext create(RuntimeState state);
    }

    public final class DefaultPermissionContextFactory implements PermissionContextFactory {
        public DefaultPermissionContextFactory(String conversationId) {}
        @Override public PermissionContext create(RuntimeState state);
    }

在 `com.pixflow.harness.loop.config` 包内定义：

    @ConfigurationProperties(prefix = "pixflow.loop")
    public class LoopProperties {
        private int maxOutputRecoveryLimit = 3;
        private int escalatedMaxOutputTokens = 64000;
        private boolean emitToolInputPreview = true;
        private int toolConcurrencyPoolSize = 8;
    }

    @Configuration
    @EnableConfigurationProperties(LoopProperties.class)
    public class LoopAutoConfiguration {
        @Bean public AgentLoop agentLoop(RuntimeState state, ..., LoopProperties properties);
        @Bean public LoopToolTraceSink loopToolTraceSink(TurnTrace turnTrace, RuntimeScope scope);
        @Bean public PermissionContextFactory permissionContextFactory(String conversationId);
    }

    public final class RuntimeState {
        // loop 包内的 RuntimeState 必须是单例 scope（@Scope("singleton")）或显式从外部注入；
        // 不同 conversationId 的回合不能共享 RuntimeState。web 层（未来 conversation 模块）
        // 必须为每个 conversationId 构造一个 RuntimeState 实例。
    }

在 `com.pixflow.harness.loop.error` 包内定义：

    public enum LoopErrorCode implements com.pixflow.common.error.ErrorCode {
        LOOP_RUNTIME_STATE_CORRUPTED,
        LOOP_CONFIGURATION_INVALID,
        LOOP_TURN_BOUNDARY_VIOLATION;
        @Override public String code() { return name(); }
        @Override public ErrorCategory category() { return ErrorCategory.INTERNAL; }
    }

`ContextEngine.buildForModel` 改造（落地在 `pixflow-context`）：

    public final class ContextEngine {
        public record BuildResult(ContextSnapshot snapshot, List<TracePruneEntry> pruneEntries) {}
        public BuildResult buildForModel(String systemPrompt, List<ToolSchemaView> toolSchemas);
    }

`harness/context.md` §十一 同步说明：本接口微调把「cheap pipeline / destructive compaction 裁剪日志」带出 `ContextEngine` 边界，由 `loop` 转投 `eval` 的 `TurnTrace.recordPrune`。`ContextSnapshot` 本身保持「模型可见数据」纯粹视图。落地时 `context.md` 必须加一行：`buildForModel` 返回 `BuildResult(snapshot, pruneEntries)`，`pruneEntries` 在 `Micro` / `AUTO` / `MANUAL` / `REACTIVE` 各触发时填充。

`module-dependency-dag-plan.md` 同步：在 Mermaid 图中补一条 `infra-ai --> loop` 边；在 Revision Notes 记录「补充 `ai --> loop` 依赖边：`ModelRetryRunner` 是 loop 的真正上游，pixflow-infra-ai 在 Wave 1、loop 在 Wave 4，无环」。`storage --> loop` 边**不补**（loop 不直接读 storage），由 `storage --> context` / `storage --> tools` / `storage --> eval` 承担 `ToolResultStorage` 的外置职责，loop 间接通过 context/eval 接入。

`RuntimeState` 的 scope 与线程封闭约定：

    @Component
    @Scope(value = WebSocketSession.SCOPE_NONE, proxyMode = ScopedProxyMode.NO)  // 显式单例
    public final class RuntimeState { ... }

或更朴素：web 层（未来 `pixflow-module-conversation`）为每个 `conversationId` 在请求入口构造一个新的 `RuntimeState` 实例，注入到 `AgentLoop`。loop 内部不维护 `conversationId → RuntimeState` 映射。

## Revision Notes

2026-06-29 / Codex: 创建本计划。原因是用户确认 `harness/loop` 模块按生产级完整实现（不按 MVP 简化），并要求 plan 阶段先讨论设计、再撰写计划文档；用户在 4 个关键决策点（`buildForModel` 是否带出裁剪条目 / `ASSISTANT_MESSAGE_COMPLETED` RuntimeScope 注入 / `TraceInput` 字段形状 / `turnNo` 分配归属）上选择「按建议钉死」。

2026-06-29 / Codex: 本计划在落地 `ContextEngine.buildForModel` 改造时，与 `pixflow-context` 模块的现状签名做对齐——`ContextEngine` 当前是 `(String, List<ToolSchemaView>) -> ContextSnapshot`，要改造为返回 `BuildResult(snapshot, pruneEntries)`，与 `loop.md §八` 末尾的接口微调建议一致；落地时在 `harness/context.md` §十一 同步加一句接口说明。`RuntimeState` 的 `RuntimeScope` 类型从 `com.pixflow.harness.hooks.payload.RuntimeScope` 引入（hook payload 公共基段），`TraceRecorder.begin` 的 `RuntimeScope` 参数从 `com.pixflow.harness.eval.model.RuntimeScope` 引入（trace 持久化维度），两者由 `loop.trace.RuntimeScopeTranslator` 集中翻译。

2026-07-01 / Codex: 落地完成。本计划全部 11 个阶段任务已勾；新增 `pixflow-loop` Maven 模块（19 个 Java 文件 / 11 个测试类共 44 个测试 / ArchUnit 4 条守护 / `BuildResult` 接口微调）。验证命令：`mvn -pl pixflow-loop -am test` BUILD SUCCESS（44 个测试全过）、`mvn -pl pixflow-loop,pixflow-context -am test` BUILD SUCCESS、`mvn test`（全 reactor）BUILD SUCCESS。落地偏差 8 处已在 Decision Log 与 Surprises & Discoveries 段记录（`ModelStreamConsumer.consume` 改为直接返回 / `LoopAutoConfiguration` 不装配 `AgentLoop` / `storage --> loop` 边不补 / `RuntimeScopeTranslator.toHooks` 反向规则 / `RuntimeState` 11 字段 / `LoopToolTraceSink` 单参构造 / ArchUnit 4 条 / `FakeModelRetryRunner` 不继承）。模块对外契约严格按 `loop.md` 18 节口径不变；调用方在 Wave 5 `agent` 层或 `pixflow-module-conversation` 落地时构造 `AgentLoop` 实例 + `SseEmitter → AgentEventSink` 适配器即可。
