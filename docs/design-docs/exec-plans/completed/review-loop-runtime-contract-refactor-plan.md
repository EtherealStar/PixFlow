# 按代码审查报告重构 loop 运行契约、工具协议与 trace 边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树、这份计划和代码审查报告，就应能完成 `pixflow-loop` 的剩余重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

这份计划要按代码审查报告继续修复 `pixflow-loop` 的运行契约、工具调用协议、权限上下文、metadata 序列化和 trace 记录边界。完成后，Agent 在正常的多次工具调用循环中仍然只按“有工具调用就继续、无工具调用就结束”的设计运行，但每个模型请求的 assistant tool-call 消息都能被正确投影回 provider，每个模型请求的 tool call 都必定得到一个 tool result，工具执行线程池会有明确背压和关闭语义，非法 subagent metadata 不会放宽权限，trace/event/metadata 在边界处不会因为 null、NaN、循环引用、负时间或 locale 差异而随机失败。

本计划不是给旧代码旁边补兼容分支。凡是审查报告指出的旧路径本身就是风险来源的，都要删除或替换：删除无界 executor 队列，删除 daemon 线程上只调用普通 `shutdown()` 的关闭路径，删除 assistant tool-call 回合被持久化为空文本 assistant 的路径，删除 executor 缺失结果时静默丢 tool result 的路径，删除 malformed 或非法 subagent metadata 降级为无约束权限上下文的路径，删除 metadata 对 NaN/Infinity、循环容器和 null 容器元素的宽松透传，删除 locale-sensitive trace key 和负 latency。项目仍处开发阶段，不为了迁移式安全保留旧 API 宽松行为或旧错误分类。

本计划也明确拒绝一个审查建议：不引入 `maxTurns`、`maxIteration` 或“工具调用次数硬上限”作为 loop 终止条件。`docs/design-docs/harness/loop.md` 明确规定本项目的正常续轮信号是“本轮 assistant 是否产生实际工具调用”，且“有工具调用就继续，无工具调用自然结束”。该设计是用户特别确认的前提。对 runaway 风险只增加观测、请求级超时对接和背压，不改变正常循环语义。

## Progress

- [x] (2026-07-09 23:40+08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md` 和当前 active exec plans，确认本计划必须放在 `docs/design-docs/exec-plans/`，并且不能和已完成的 `review-upload-loop-safety-refactor-plan.md` 重复实现范围。
- [x] (2026-07-09 23:45+08:00) 阅读 `docs/design-docs/harness/loop.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/infra/permission.md`，确认 loop 续轮只看 tool calls、无 maxTurns、工具错误必须回填模型、permission fail-closed、context 必须保 tool call/result 配对。
- [x] (2026-07-09 23:55+08:00) 阅读代码审查报告和热点代码，确认当前工作树仍存在 executor 无界队列、普通 shutdown、assistant tool-call 空文本投影、missing tool result 静默丢弃、ReactiveCompactionGate 防抖位先置、metadata NaN/循环/null 策略、trace locale/时间修正等剩余问题。
- [x] (2026-07-10 00:05+08:00) 新建本中文 ExecPlan，明确不加入 maxTurns，转而按协议完整性、资源背压、权限 fail-closed、metadata/trace 契约收紧来做完整重构。
- [x] (2026-07-09 22:20+08:00) 第一阶段实施：新增 `AssistantToolCall`，扩展 `MessageMetadata` / `ChatMessage` / `DefaultChatModelClient` / `AgentLoop`，assistant tool-call 回合保存完整 `id/name/argumentsJson`，tool-only assistant 不再投影为空 `TextPart`。
- [x] (2026-07-09 22:23+08:00) 第二阶段实施：重构 `mergeToolResults`，按 provider 原始 tool call 顺序输出结果；executor 返回 null、空 id、重复 id 或漏回结果时，缺失调用会补 `tool_execution_missing_result` 结构化 internal tool error。
- [x] (2026-07-09 22:25+08:00) 第三阶段实施：重构 loop executor 配置，新增 `toolQueueCapacity`、`toolShutdownTimeoutSeconds`、`MAX_ESCALATED_OUTPUT_TOKENS` 与 `GracefulThreadPoolExecutor`；`loopToolExecutor` 使用有界 `LinkedBlockingQueue`、`CallerRunsPolicy` 和 `shutdownGracefully`。
- [x] (2026-07-09 22:26+08:00) 第四阶段实施：重构权限上下文与恢复 gate；非法 subagent metadata fail-closed，`ReactiveCompactionGate` 在 `reactiveCompact` 成功后才标记防抖位。
- [x] (2026-07-09 22:28+08:00) 第五阶段实施：重构 metadata、attachment、event、error code、trace 边界；metadata 拒绝 NaN/Infinity、循环容器和 null 容器值，附件引用只允许 `object://` / `attachment://`，assistant completed payload 固定为 `messageId`，trace key 使用 `Locale.ROOT` 并修正时间戳/unknown tool。
- [x] (2026-07-09 22:45+08:00) 第六阶段实施：补齐重点测试与文档/Javadocs，相关模块编译通过；用户随后反馈已完成全面测试且测试成功。

## Surprises & Discoveries

- Observation: `review-upload-loop-safety-refactor-plan.md` 已完成一批 loop 重构，但它只把 executor 从 `AgentLoop` 内部移到 Spring Bean，并限制 `toolConcurrencyPoolSize` 为 1..64；当前 `loopToolExecutor` 仍使用无界 `LinkedBlockingQueue`，关闭仍是 `destroyMethod="shutdown"`。
  Evidence: `pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java` 中 `new LinkedBlockingQueue<>()` 没有容量参数，Bean 注解仍是 `destroyMethod = "shutdown"`。

- Observation: `AgentLoop` 在模型返回工具调用时把 assistant 消息保存为 `Message.assistant(finalText)`，只把 tool call ids 放入 metadata；如果 finalText 为空，下一轮 `toChatMessage` 会构造 `ChatMessage.TextPart("")`，而 `TextPart` 拒绝 blank 文本。
  Evidence: `AgentLoop.java` 中 `Message assistantMsg = Message.assistant(finalText)`，随后 `toChatMessage` 对 `ASSISTANT` 始终 `new ChatMessage.TextPart(content)`；审查报告指出 `ChatMessage.TextPart` 构造器拒绝空白文本。

- Observation: context 模块已有 `Message.assistantToolCall(String content, String toolCallId)` 和 `MessageMetadata.TOOL_CALL_IDS`，但只保存单个 id，不足以把 provider 需要的 tool call name 与 arguments 重建回下一轮模型请求。
  Evidence: `rg -n "assistantToolCall|TOOL_CALL_IDS" pixflow-context pixflow-loop` 命中 context 测试和 `Message.java`，当前 loop 没有使用完整 tool-call payload。

- Observation: `AgentLoop.mergeToolResults` 对 executor 未返回的 tool call 静默跳过，导致 message store 里 tool result 数量可能少于 assistant 请求数量。
  Evidence: `AgentLoop.java` 中只有 `if (result != null) merged.add(result);`，没有 `else` 生成错误结果。

- Observation: `docs/design-docs/harness/loop.md` 明确说“无 maxTurns”和“不设迭代上限”，这与审查报告中“加 max-iteration guard”的建议冲突。
  Evidence: `loop.md` 的设计原则写明“不设迭代上限（无 maxTurns）”，并解释运行边界由模型自然收敛、context 预算/压缩、usage 可观测和请求级超时兜底。

- Observation: `MetadataValues.normalizeValue` 当前接受所有 `Number`，包括 `Double.NaN`、`Double.POSITIVE_INFINITY`、`Float.NaN` 和 infinities；递归处理 Map/List/Set 时没有 identity cycle 检测；List 路径使用 `List.copyOf` 会拒绝 null 元素，而 Map 路径会保留 null value。
  Evidence: `pixflow-loop/src/main/java/com/pixflow/harness/loop/MetadataValues.java` 中 `value instanceof Number` 直接返回，递归调用没有 `seen` 集合，Iterable 分支最后 `List.copyOf(copy)`。

- Observation: `ReactiveCompactionGate` 现在先标记 `state.markReactiveCompactAttempted()`，再调用 `compactionService.reactiveCompact`。如果 compaction 抛异常，同一 state 会被标记为已 compact，但实际没有得到 retry decision。
  Evidence: `ReactiveCompactionGate.java` 中防抖位设置在 `reactiveCompact(store, error, metadata)` 前。

- Observation: trace 时间修正当前会在任一时间戳缺失时同时覆盖 started/finished，可能丢掉另一侧有效时间；hook trace key 使用 `event.name().toLowerCase()`，受 JVM 默认 locale 影响。
  Evidence: `TraceFanout.java` 和 `LoopToolTraceSink.java` 中 `started <= 0L || finished <= 0L` 分支把两者都设为 `now`；`TraceFanout.fanoutHookSpan` 使用 `event.name().toLowerCase()`。

- Observation: 单独运行 `mvn -pl pixflow-loop ... test` 时，若不加 `-am`，loop 测试可能使用本地仓库中的旧 `pixflow-infra-ai` jar，从而看不到新加入的 `ChatMessage.ToolCallPart` / `ToolResultPart`。
  Evidence: 本轮曾观察到 loop 测试编译报 `找不到符号 类 ToolCallPart` 和 `找不到符号 类 ToolResultPart`；使用 reactor 方式包含 `pixflow-infra-ai,pixflow-loop -am` 才能保证新 provider-neutral 消息类型进入测试编译路径。

- Observation: 一次完整聚合测试曾被上游 `pixflow-eval` 的既有测试 `TraceRecorderTest.externalizesLargePayloadAfterSanitizingAndReplaysIt` 提前阻断；该失败发生在执行到 context/infra-ai/loop 之前，不属于本计划修改范围。之后用户反馈已进行全面测试并成功。
  Evidence: Maven reactor 在 `pixflow-eval` 阶段报 `NoSuchElementException: No value present`，后续 `pixflow-context`、`pixflow-infra-ai`、`pixflow-loop` 均被 SKIPPED；随后用户消息说明“我进行了全面的测试已成功”。

## Decision Log

- Decision: 不实现 `maxTurns`、`maxIteration` 或 `maxToolCalls` 硬终止条件。
  Rationale: 用户明确说明“有工具调用就不停止循环，没用工具调用就继续循环是有意设计”中的核心意思是：tool-call 循环不因次数上限中断；设计文档也明确无 maxTurns。硬上限会把合法长任务变成错误终止，破坏 `loop.md` 的状态机。运行风险通过有界 executor、请求级超时、usage/iteration 指标和 trace 可观测处理。
  Date/Author: 2026-07-10 / Codex

- Decision: assistant tool-call 消息必须成为 provider-neutral 的一等消息形态，不能继续用空文本 assistant + metadata ids 近似表达。
  Rationale: provider 协议要求 assistant tool call 与后续 tool result 成对出现。只保存 ids 不足以重建 name/arguments，空文本 assistant 又会触发 `TextPart` blank 校验。正确机制是在 context 消息 metadata 中持久保存完整 tool call payload，并在 infra-ai 的 `ChatMessage` 或 loop 投影中表达 assistant tool calls。
  Date/Author: 2026-07-10 / Codex

- Decision: 每个模型请求的 tool call 必须得到一个 tool result，即使 executor 返回缺失结果也要补结构化 internal error。
  Rationale: 模型协议按 tool_call_id 配对。如果少一个 tool result，下一轮请求就是不完整 conversation，可能被 provider 拒绝或让模型误判工具状态。补 error result 比静默丢弃更安全，也符合 tools 文档“工具失败回填模型，不崩主循环”的约定。
  Date/Author: 2026-07-10 / Codex

- Decision: loop tool executor 使用有界队列和显式拒绝策略，默认拒绝策略为 `CallerRunsPolicy`。
  Rationale: 固定线程池配无界队列会把压力转成堆内存增长。`CallerRunsPolicy` 会让提交线程执行任务，从而自然拖慢当前 loop，提供背压；它不丢任务，适合工具调用协议必须完整回填的场景。若未来需要快速失败，可再通过配置增加 domain-specific rejection handler。
  Date/Author: 2026-07-10 / Codex

- Decision: `loopToolExecutor` 关闭改为优雅关闭 wrapper，先等待已提交任务完成，超时后再 `shutdownNow`。
  Rationale: 工具调用可能写外部系统或产生可观测事件，普通 `shutdown()` 返回后如果 JVM 退出，daemon worker 可能被直接丢弃。优雅关闭让 Spring context 停止时尽量完成在途工具，超时后再强停，符合服务进程退出语义。
  Date/Author: 2026-07-10 / Codex

- Decision: 非法 subagent metadata fail-closed，不再退化为无 subagent 约束。
  Rationale: subagent metadata 是权限边界的一部分。格式错误如果变成“主 Agent/无约束”，会放宽工具可见性和副作用权限。默认实现应抛受控异常或返回最保守约束，本计划选择抛 `PixFlowException` 或 `IllegalStateException`，由 loop 不可恢复路径记录并上抛。
  Date/Author: 2026-07-10 / Codex

- Decision: metadata 统一成 JSON-safe、无循环、有限数字、无 null 容器元素的不可变结构；`RuntimeState.putMetadata(key, null)` 保留“删除 key”语义。
  Rationale: 事件、trace、错误详情和上下文 metadata 都会被序列化或 Map.copyOf。NaN/Infinity 不是标准 JSON 数字，循环容器会 StackOverflow，null 容器元素在不同路径行为不一致。保留 putMetadata 的删除语义不会污染持久 metadata，因为 null 不进入 `MetadataValues.normalizeValue`。
  Date/Author: 2026-07-10 / Codex

- Decision: POM parent `1.0.0-SNAPSHOT` 不在本计划中单独改成 `1.0.0`。
  Rationale: 根项目仍处开发态，没有可引用的 release parent。单独修改 `pixflow-loop/pom.xml` 会使 Maven 无法解析父 POM。这是仓库发布流程决策，不应在 loop 运行契约修复中伪造 release。
  Date/Author: 2026-07-10 / Codex

## Outcomes & Retrospective

本计划已完成代码重构。当前实现保持 loop 的设计语义不变：仍然只按“有工具调用就继续、无工具调用就结束”运行，没有新增 `maxTurns` 或工具调用次数硬上限。变化集中在协议完整性、资源背压、权限 fail-closed、metadata/event/trace 边界稳定性。

已完成的主要结果：

- `pixflow-context`：新增 `AssistantToolCall` provider-neutral 结构；`MessageMetadata` 支持 `assistantToolCalls` 完整载荷；`Message.assistantToolCall` 仍保留旧测试重载，但新路径保存 `id/name/argumentsJson`。
- `pixflow-infra-ai`：`ChatMessage` 新增 `ToolCallPart` 和 `ToolResultPart`；`DefaultChatModelClient` 把 assistant tool calls 投影为 OpenAI-compatible `tool_calls`，把 tool result 投影为带 `tool_call_id` 的 `tool` message。
- `pixflow-loop`：`AgentLoop` 在模型返回 tool calls 时创建 assistant tool-call message，不再把空 finalText 投影为空 `TextPart`；tool result 消息投影为 `ToolResultPart`；`mergeToolResults` 保证每个 requested tool call 都有 tool result，executor 缺失时补 `tool_execution_missing_result`。
- `pixflow-loop` executor：`LoopProperties` 增加 `toolQueueCapacity`、`toolShutdownTimeoutSeconds` 与输出 token 上限校验；`LoopAutoConfiguration` 使用有界队列、`CallerRunsPolicy` 和 `GracefulThreadPoolExecutor.shutdownGracefully`。
- `pixflow-loop` security/recovery：非法 subagent metadata fail-closed；`ReactiveCompactionGate` 只在 reactive compaction 成功后标记防抖位。
- `pixflow-loop` metadata/event/trace：metadata 拒绝非有限浮点、循环容器和 null 容器值；`Attachment` 只接受 `object://` / `attachment://` durable 引用并拒绝控制字符；`AgentEvent.assistantCompleted` payload 固定为 `messageId`；`LoopErrorCode` 使用显式稳定 code；trace 使用 `Locale.ROOT`、非负 latency、单侧时间戳修正和 `unknown_tool` sentinel；`RuntimeScopeTranslator` 显式映射 `main/subagent/worker`。
- 测试覆盖：新增或更新测试覆盖 assistant tool-call provider 投影、executor missing result、metadata NaN/Infinity/cycle/null、attachment durable reference、assistant completed payload、permission fail-closed、executor 配置边界。

本轮执行中观察到的验证结果：

    mvn -pl pixflow-context,pixflow-infra-ai,pixflow-loop -am -DskipTests compile

结果：BUILD SUCCESS。说明 context、infra-ai、tools、loop 及其上游依赖在当前 reactor 内可以编译通过。

    mvn -pl pixflow-infra-ai -Dtest=DefaultChatModelClientTest test

结果：BUILD SUCCESS。`DefaultChatModelClientTest` 3 个测试通过，覆盖 streaming tool call 累积以及 assistant tool_calls / tool result provider JSON 投影。

一次完整聚合测试曾在 `pixflow-eval` 的既有测试 `TraceRecorderTest.externalizesLargePayloadAfterSanitizingAndReplaysIt` 阶段失败并提前中断，未执行到本计划相关模块；随后用户反馈已经进行了全面测试且测试成功。因此本计划按用户提供的完整验证结果标记为完成。

POM parent 仍保留 `1.0.0-SNAPSHOT`，原因同 Decision Log：根项目仍处开发态，当前没有可引用的 release parent，不在 loop 运行契约修复中伪造版本。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。本计划聚焦 Maven 模块 `pixflow-loop`，必要时小范围触达 `pixflow-context` 和 `pixflow-infra-ai`，因为 assistant tool-call 消息的正确表达跨越三层：loop 从模型结果得到 tool calls，context 保存会话内消息链，infra-ai 把 provider-neutral message 投影为具体模型请求。

`pixflow-loop` 是 Agent think-act-observe 主循环模块。这里的“主循环”是指一次用户回合内重复执行“调用模型、解析工具调用、执行工具、把工具结果回填模型”的过程。它的正常终止条件只有一个：模型本轮不再请求工具调用。只要模型请求了工具调用，loop 就执行工具并继续下一次模型调用。这个设计由 `docs/design-docs/harness/loop.md` 明确规定，本计划不会改变。

`pixflow-context` 是运行期工作内存模块。它的 `Message` 和 `MessageStore` 保存本回合消息链，并通过 `TranscriptPort` 写穿透到 `pixflow-session` 的 MySQL `message` 表。这里的“assistant tool-call 消息”是 provider-neutral 的 assistant 消息：它不是普通文本，而是“模型请求调用某个工具，带 toolCallId、tool name 和 arguments”。它必须和后续 `TOOL_RESULT` 消息按 `toolCallId` 配对。

`pixflow-infra-ai` 是 provider-neutral AI 接口模块。它提供 `ChatMessage`、`ToolCall`、`ChatRequest` 和具体 provider adapter。当前 `ChatMessage.TextPart` 拒绝 blank 文本，因此 loop 不能把只有工具调用的 assistant 消息投影为空文本。

`pixflow-tools` 是工具执行管线模块。它的设计要求任何工具失败都变成 `ToolExecutionResult(error=true)` 回填模型，而不是让主循环崩溃。`AgentLoop.mergeToolResults` 属于 loop 和 tools 的接缝：它必须保证 tools executor 的输出和模型请求的 tool calls 一一对应。

`pixflow-permission` 是硬权限边界模块。`DefaultPermissionContextFactory` 根据 `RuntimeState.metadata` 构造 `PermissionContext`，其中 subagent 约束决定 child runtime 是否只读、可见工具是否裁剪。非法 subagent metadata 必须 fail-closed。

关键文件如下：

- `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java`：主循环、模型请求投影、tool call 解析、tool result 合并。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java`：`loopToolExecutor` Bean 创建与生命周期。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopProperties.java`：loop 配置绑定与 setter 校验。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/MetadataValues.java`：metadata 归一化边界。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/RuntimeState.java`：回合内运行态和 metadata 扩展位。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/Attachment.java`：用户附件 durable 引用。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/event/AgentEvent.java` 和 `AgentEventSink.java`：SSE/事件流载体和接出 SPI。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/permission/PermissionContextFactory.java` 与 `DefaultPermissionContextFactory.java`：权限上下文构造。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/recovery/ReactiveCompactionGate.java` 与 `OutputInterruptHandler.java`：CONTEXT_LIMIT 与输出截断恢复。
- `pixflow-loop/src/main/java/com/pixflow/harness/loop/trace/TraceFanout.java`、`LoopToolTraceSink.java`、`RuntimeScopeTranslator.java`：trace 转投与 runtime scope 映射。
- `pixflow-context/src/main/java/com/pixflow/harness/context/model/Message.java` 和 `MessageMetadata.java`：消息模型和 metadata。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatMessage.java`、`ToolCall.java`、provider adapter：模型请求消息形态。

## Plan of Work

第一阶段重构 assistant tool-call 消息建模与投影。先阅读 `pixflow-context/src/main/java/com/pixflow/harness/context/model/Message.java`、`MessageMetadata.java`、`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatMessage.java` 和 provider adapter 对 `ChatMessage` 的转换逻辑。目标是让 assistant tool-call 消息拥有完整 tool call payload，而不是只存 id。推荐新增一个 provider-neutral 结构，例如在 `pixflow-context` 中定义 `AssistantToolCall` record，字段为 `id`、`name`、`argumentsJson`，并让 `MessageMetadata` 保存不可变 `List<AssistantToolCall>` 或 JSON-safe list of maps。也可以扩展 `ChatMessage` 增加 `ToolCallPart`，但必须保证 provider adapter 能把它投影到实际模型 API 的 assistant tool_calls 格式。实施后，`AgentLoop` 在 `outcome.toolCalls()` 非空时创建 assistant tool-call message，finalText 为空也不能创建 blank `TextPart`。`toChatMessage` 看到 assistant tool-call metadata 时必须生成包含 tool call part 的 assistant message，而不是文本 part。

第二阶段修复 tool result 配对完整性。修改 `AgentLoop.mergeToolResults`：对每个 `ParsedToolCall`，如果 parseError 存在，保持现有 `invalid_tool_input` error result；如果 parsed tool call 存在但 executor 没有按 `toolCallId` 返回结果，生成 `ToolExecutionResult.error(call.toolCallId(), call.name(), "tool_execution_missing_result: executor returned no result for tool call", metadata)`。metadata 至少包含 `errorCategory=INTERNAL`、`recovery=SKIP`、`missingToolResult=true`。同时要处理 executor 返回 null result、blank toolCallId 或重复 toolCallId 的边界：null result 跳过并为对应 call 补 missing error；重复结果以第一个为准并在后续重复上加 trace metadata 或测试固定行为。不要让工具结果数量少于模型请求数量。

第三阶段重构 loop executor 背压与关闭。修改 `LoopProperties`，新增 `toolQueueCapacity`、`toolShutdownTimeoutSeconds` 和 `MAX_ESCALATED_OUTPUT_TOKENS`。`toolQueueCapacity` 默认建议 256，setter 要求 1..10000；`toolShutdownTimeoutSeconds` 默认建议 30，setter 要求 1..300；`escalatedMaxOutputTokens` 要求 1..128000。修改 `LoopAutoConfiguration.loopToolExecutor`，使用 `new LinkedBlockingQueue<>(queueCapacity)` 和 `new ThreadPoolExecutor.CallerRunsPolicy()`。为了优雅关闭，新增包内类 `GracefulExecutorService` 或更简单的 bean 类型 `LoopToolExecutor`，内部持有 `ThreadPoolExecutor`，暴露 `ExecutorService` 委托方法，并提供 `shutdownGracefully()`。Bean 的 `destroyMethod` 改为 `shutdownGracefully`。如果不想新增完整 wrapper，也可以返回一个继承 `ThreadPoolExecutor` 的小类 `GracefulThreadPoolExecutor`，新增 `shutdownGracefully()` 方法。线程可以继续是 daemon，但优雅关闭必须等待。

第四阶段重构权限上下文与恢复 gate。修改 `PermissionContextFactory` Javadoc，方法级写明 `RuntimeState` 必须非 null，`conversationId` 必须非 blank，非法 `subagent` metadata 必须拒绝创建上下文。修改 `DefaultPermissionContextFactory`，如果 `subagent` metadata 是 Map 但缺必填字段、类型不匹配、allowed/disallowed 工具集合不是字符串集合，直接抛受控异常；如果 metadata 完全没有 subagent，才表示无子 Agent 约束。修改 `ReactiveCompactionGate.onContextLimit`，开头 `Objects.requireNonNull(error, "error")`，并把 `state.markReactiveCompactAttempted()` 移到 `compactionService.reactiveCompact` 成功之后。如果 reactiveCompact 抛异常，不要把 state 标为已尝试。`OutputInterruptHandler` 的双 append 原子性本阶段不强行引入 batch API，但必须新增测试记录当前失败风险；如果 context 已有 batch append 或容易新增 `appendMessagesAtomically`，则把截断 assistant 和 continuation user 合并为单个 batch。

第五阶段收紧 metadata、Attachment、event 和 error code 契约。修改 `MetadataValues`，引入私有递归方法 `normalizeValue(value, seen)`，`seen` 用 `IdentityHashMap` backing set，对 Map、Set、Iterable 入栈前检测循环，finally 出栈。`Double` 与 `Float` 必须 `isFinite`，其它 `Number` 如 `BigDecimal`、`Integer`、`Long` 可保留；如果要更严格，可只允许常见 JSON number 类型，但本计划建议先保守拒绝非有限浮点。容器中的 null value 统一拒绝，错误信息写清 `metadata container values must not be null`。`RuntimeState.putMetadata(key, null)` 继续表示删除 key，不调用 normalize。修改 `Attachment`，reference trim 后拒绝 ISO control char，只允许 `object://` 或 `attachment://`。修改 `AgentEvent.assistantCompleted` 签名为 `assistantCompleted(String finalText, String messageId, Map<String,Object> metadata)`，payload 固定为 `Map.of("messageId", messageId)`。修改 `AgentEventSink` Javadoc，写明 event 非 null；适配器入口应 `Objects.requireNonNull(event, "event")`，`NOOP` 也不要静默接受 null。修改 `LoopErrorCode`，每个枚举持显式 code 字符串和 category，把 `LOOP_CONFIGURATION_INVALID` 改成 server-side category，避免配置错误被渲染成 HTTP 400。

第六阶段收紧 trace 与 runtime scope。修改 `TraceFanout.fanoutHookSpan`，用 `Locale.ROOT` 做 lower-case；blocked hook 的 `TraceError.recordedAt` 和 span startedAt 使用同一个 `Instant`；`latencyMs` 用 `Math.max(0L, latencyMs)`。修改 `TraceFanout.fanoutToolTraceEvent` 和 `LoopToolTraceSink.record`，当 started 或 finished 只有一侧缺失时只修缺失的一侧，保留有效时间；finished < started 时 finished = started 并标 `timestampCorrected=true`。在传给 `TraceError` details 前过滤或拒绝 null metadata value，避免 `Map.copyOf` 抛出 NPE。若 `event.toolName()` blank，使用 `"unknown_tool"` 并在 metadata 标注 `toolNameMissing=true`。修改 `RuntimeScopeTranslator`，显式映射 MAIN、SUB_AGENT、WORKER，hooks 侧字符串必须是 `"main"`、`"subagent"`、`"worker"`，不要从 enum name 派生 `"sub_agent"`。

第七阶段删除死代码和更新文档。删除 `RuntimeState.copyMetadata` 和 `DefaultPermissionContextFactory.freeze` 这类生产未使用 helper。更新 `LoopAutoConfiguration` 类 Javadoc，说明暴露 `LoopProperties`、`PermissionContextFactory` 和 `loopToolExecutor`。更新 `AgentTurnRunner` Javadoc，让它只描述真实 SPI 参数。更新 `docs/design-docs/harness/loop.md` Revision Notes，记录本次重构：assistant tool-call 一等建模、executor 有界队列和优雅关闭、missing tool result error、permission fail-closed、metadata finite/cycle-safe、trace locale/timestamp 稳定，并再次注明无 maxTurns 仍是设计。必要时更新 `docs/design-docs/harness/context.md` 和 `docs/design-docs/infra/ai.md`，说明 assistant tool-call 消息的 provider-neutral 表达。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认当前风险点：

    rg -n "new LinkedBlockingQueue<\\>\\(|destroyMethod\\s*=\\s*\"shutdown\"|if \\(result != null\\) \\{|event\\.name\\(\\)\\.toLowerCase\\(\\)|assistantCompleted\\(String finalText, Object payload|copyMetadata\\(|Double\\.isFinite|Float\\.isFinite|IdentityHashMap|startsWith\\(\"object://\"\\)" pixflow-loop/src/main/java pixflow-context/src/main/java pixflow-infra-ai/src/main/java

预期实施前会命中若干旧路径。实施完成后，生产代码不应再命中无界队列、普通 shutdown、assistantCompleted Object payload、locale-sensitive lower-case、缺少 finite/cycle guard 等旧风险形态。允许命中测试中验证旧行为不存在的字符串。

第一组改 tool-call 消息协议：

    pixflow-context/src/main/java/com/pixflow/harness/context/model/Message.java
    pixflow-context/src/main/java/com/pixflow/harness/context/model/MessageMetadata.java
    pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatMessage.java
    pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java

新增或更新测试：

    pixflow-loop/src/test/java/com/pixflow/harness/loop/AgentLoopToolCallProtocolTest.java
    pixflow-context/src/test/java/com/pixflow/harness/context/model/MessageToolCallMetadataTest.java
    pixflow-infra-ai/src/test/java/com/pixflow/infra/ai/chat/DefaultChatModelClientTest.java

第二组改 executor：

    pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopProperties.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/config/GracefulThreadPoolExecutor.java

新增或更新测试：

    pixflow-loop/src/test/java/com/pixflow/harness/loop/config/LoopAutoConfigurationTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/config/LoopPropertiesTest.java

第三组改 permission / recovery：

    pixflow-loop/src/main/java/com/pixflow/harness/loop/permission/PermissionContextFactory.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/permission/DefaultPermissionContextFactory.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/recovery/ReactiveCompactionGate.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/recovery/OutputInterruptHandler.java

新增或更新测试：

    pixflow-loop/src/test/java/com/pixflow/harness/loop/DefaultPermissionContextFactoryTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/AgentLoopErrorRecoveryTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/recovery/ReactiveCompactionGateTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/recovery/OutputInterruptHandlerTest.java

第四组改 metadata / event / trace：

    pixflow-loop/src/main/java/com/pixflow/harness/loop/MetadataValues.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/Attachment.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/RuntimeState.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/event/AgentEvent.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/event/AgentEventSink.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/error/LoopErrorCode.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/trace/TraceFanout.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/trace/LoopToolTraceSink.java
    pixflow-loop/src/main/java/com/pixflow/harness/loop/trace/RuntimeScopeTranslator.java

新增或更新测试：

    pixflow-loop/src/test/java/com/pixflow/harness/loop/MetadataValuesTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/AttachmentTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/RuntimeStateTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/AgentEventTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/TraceFanoutTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/LoopToolTraceSinkTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/RuntimeScopeTranslatorTest.java

运行模块验证：

    mvn -pl pixflow-loop -am test
    mvn -pl pixflow-context,pixflow-infra-ai,pixflow-loop -am test
    mvn -pl pixflow-app -am -DskipTests compile

如果第一条因 sandbox 启动 Maven 失败，按权限规则重新运行同一 Maven 命令并申请提升。成功输出应包含类似：

    [INFO] Reactor Summary:
    [INFO] PixFlow Loop ... SUCCESS
    [INFO] BUILD SUCCESS

收尾检查：

    rg -n "new LinkedBlockingQueue<\\>\\(|destroyMethod\\s*=\\s*\"shutdown\"|assistantCompleted\\(String finalText, Object payload|event\\.name\\(\\)\\.toLowerCase\\(\\)|copyMetadata\\(|freeze\\(" pixflow-loop/src/main/java
    rg -n "tool_execution_missing_result|CallerRunsPolicy|shutdownGracefully|IdentityHashMap|Double\\.isFinite|Float\\.isFinite|Locale\\.ROOT|unknown_tool|object://|attachment://" pixflow-loop/src/main/java pixflow-loop/src/test/java
    rg -n "<version>.*-SNAPSHOT</version>" pixflow-loop/pom.xml pom.xml

第一条不应命中生产旧风险路径。第二条应命中新机制实现或测试。第三条预计仍命中根项目和子模块 parent snapshot；这不是本计划的代码缺口，要在 Outcomes 中记录为仓库发布状态。

## Validation and Acceptance

assistant tool-call 协议验收：构造 fake model 第一轮返回 `finalText=""` 且 `toolCalls=[ToolCall("tc1","search","{}")]`，第二轮返回普通最终文本。运行 `AgentLoop.stream` 时，第一轮不得抛 `TextPart must not be blank` 或类似异常；第二轮发给 fake model 的 request 必须包含 assistant tool-call 消息和对应 tool result。新增测试在旧实现下失败，在新实现下通过。

missing tool result 验收：构造 fake `ToolExecutor`，收到两个 tool calls 但只返回其中一个 result。loop 必须 append 两条 tool result 消息；缺失那条内容为 `tool_execution_missing_result`，metadata 中含 `errorCategory=INTERNAL` 和 `recovery=SKIP`。下一轮模型请求不缺配对。

executor 背压验收：Spring context 创建 `loopToolExecutor` 时，内部 queue capacity 等于 `pixflow.loop.tool-queue-capacity`。设置 `tool-concurrency-pool-size=1`、`tool-queue-capacity=1` 后提交超过容量的任务不会无限排队；`CallerRunsPolicy` 会让提交线程执行或阻塞当前流。context close 时调用 `shutdownGracefully`，测试应能观察到已提交任务完成或超时后 `shutdownNow` 被调用。

权限 fail-closed 验收：`RuntimeState.metadata.subagent` 是 malformed map、allowedTools 含非字符串元素、readOnly 是非 boolean 字符串时，`DefaultPermissionContextFactory.create(state)` 抛明确异常；没有 subagent metadata 时仍创建主 Agent 权限上下文。

reactive compaction 验收：`compactionService.reactiveCompact` 抛异常时，`state.hasAttemptedReactiveCompact()` 仍为 false；成功时才为 true，并返回 `REACTIVE_COMPACT_RETRY`。

metadata 验收：`MetadataValues.immutableCopy` 拒绝 `Double.NaN`、`Float.POSITIVE_INFINITY`、自引用 Map、自引用 List、集合中的 null 元素；正常嵌套 Map/List/Set 被防御性复制为不可变结构。修改输入 map/list 后，已归一化 metadata 不变化。

Attachment 验收：`new Attachment("id","image","http://x", Map.of())`、`file://...`、包含换行或控制字符的 reference 都失败；`object://bucket/key` 和 `attachment://id` 成功。

AgentEvent 验收：`AgentEvent.assistantCompleted("done", "msg-1", Map.of())` 的 payload 是包含 `messageId=msg-1` 的 map；调用者不能传任意 String payload 破坏 SSE 投影。

trace 验收：在 Turkish locale 下运行 `TraceFanoutTest`，hook trace name 仍是 ASCII lower-case，如 `loop.hook.assistant_message_completed`，不出现 dotless i。负 latency 被 clamp 到 0。只有 started 或 finished 缺失时，另一侧有效时间保留。toolName blank 时 trace name 是 `unknown_tool`，metadata 标注 `toolNameMissing=true`。

LoopErrorCode 验收：`LOOP_CONFIGURATION_INVALID.code()` 是显式稳定字符串，category 是 server-side 类型，不会映射成客户端 400。测试应证明 rename enum 常量不会改变 code 字符串，至少通过断言当前 code 值固定。

无 maxTurns 验收：保留或新增测试证明模型连续返回三轮、五轮或更多 tool calls 时 loop 不因次数上限中断，直到 fake model 返回无 tool calls 才 `COMPLETED`。不要新增任何 `maxTurns` 配置项。

## Idempotence and Recovery

本计划可以分阶段实施。每个阶段完成后运行对应测试，失败时不要恢复旧风险路径，而要沿新契约修复调用点或测试替身。

assistant tool-call 消息重构可能同时影响 context、infra-ai 和 loop。推荐先新增新消息/part 类型和测试，再改 loop 使用，最后删除旧的空文本近似路径。若 provider adapter 暂时不能完整支持新 part，应先在 infra-ai 的 fake/test adapter 中验证 provider-neutral 形态，再按真实 adapter 的 wire format 补投影；不要为了编译通过继续生成 blank `TextPart`。

executor 关闭重构可重复运行。Spring Bean 名称 `loopToolExecutor` 不应改变，避免破坏 agent/conversation 构造注入。若新增 wrapper 类导致已有代码要求 `ExecutorService` 类型不兼容，wrapper 必须实现或继承 `ExecutorService`，而不是要求调用方改成新接口。

permission fail-closed 可能让旧测试中 malformed subagent metadata 从“无约束成功”变为异常。正确处理方式是更新测试表达新安全契约；不要保留宽松 fallback。

metadata 收紧可能暴露现有调用点把 null 放进 metadata map。执行者应逐个调用点决定删除 null key 还是提供明确 sentinel 字符串，不要在 `MetadataValues` 里为兼容保留 null 容器元素。`RuntimeState.putMetadata(key, null)` 的删除语义是唯一允许的 null 写入入口。

POM snapshot 检查如果仍命中，不要单独改子模块 parent。只有当根项目发布了非 snapshot parent 且本地/远端 Maven 可解析时，才统一更新。

## Artifacts and Notes

审查报告对应的剩余关键证据：

    LoopAutoConfiguration:
        @Bean(name = LOOP_TOOL_EXECUTOR_BEAN, destroyMethod = "shutdown")
        new LinkedBlockingQueue<>()
        t.setDaemon(true)

    AgentLoop:
        Message assistantMsg = Message.assistant(finalText)
        metadata only stores MessageMetadata.TOOL_CALL_IDS
        toChatMessage ASSISTANT always creates new ChatMessage.TextPart(content)
        mergeToolResults only adds result when result != null

    ReactiveCompactionGate:
        state.markReactiveCompactAttempted()
        compactionService.reactiveCompact(store, error, metadata)

    MetadataValues:
        value instanceof Number accepted without finite check
        recursive Map / Iterable without cycle detection
        Map branch preserves null, List.copyOf branch rejects null later

    TraceFanout / LoopToolTraceSink:
        event.name().toLowerCase()
        if started <= 0 || finished <= 0 then both become now
        latencyMs forwarded directly

目标机制摘要：

    assistant tool call:
        model tool_calls -> assistant message with complete tool call payload -> append tool results one-for-one -> next request preserves provider protocol

    executor:
        fixed worker count + bounded queue + CallerRunsPolicy -> graceful shutdown with awaitTermination -> shutdownNow only after timeout

    permission:
        missing subagent metadata means main runtime; malformed subagent metadata means fail-closed exception; no malformed metadata can broaden access

    metadata:
        JSON-safe finite immutable values -> identity cycle guard -> no null container values -> RuntimeState null means delete only

    trace:
        Locale.ROOT stable names -> monotonic non-negative durations -> one timestamp for nested error/span -> safe unknown_tool sentinel

## Interfaces and Dependencies

`LoopProperties` must expose and validate these fields:

    public static final int MAX_TOOL_CONCURRENCY_POOL_SIZE = 64;
    public static final int MAX_TOOL_QUEUE_CAPACITY = 10_000;
    public static final int MAX_ESCALATED_OUTPUT_TOKENS = 128_000;

    private int toolConcurrencyPoolSize = 8;
    private int toolQueueCapacity = 256;
    private int toolShutdownTimeoutSeconds = 30;
    private int escalatedMaxOutputTokens = 64_000;

    public int toolQueueCapacity()
    public void setToolQueueCapacity(int value)
    public int toolShutdownTimeoutSeconds()
    public void setToolShutdownTimeoutSeconds(int value)

`LoopAutoConfiguration` must keep the existing bean name and publish an `ExecutorService`:

    public static final String LOOP_TOOL_EXECUTOR_BEAN = "loopToolExecutor";

    @Bean(name = LOOP_TOOL_EXECUTOR_BEAN, destroyMethod = "shutdownGracefully")
    @ConditionalOnMissingBean(name = LOOP_TOOL_EXECUTOR_BEAN)
    public ExecutorService loopToolExecutor(LoopProperties properties)

If Java type constraints make `destroyMethod="shutdownGracefully"` awkward on raw `ExecutorService`, define:

    public final class GracefulThreadPoolExecutor extends ThreadPoolExecutor {
        private final long shutdownTimeoutSeconds;
        public void shutdownGracefully() {
            shutdown();
            if (!awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                shutdownNow();
            }
        }
    }

`AgentLoop.mergeToolResults` must preserve provider order and output one result per parsed tool call:

    parsed parseError -> parseError result
    parsed toolCall + executor result -> executor result
    parsed toolCall + missing executor result -> ToolExecutionResult.error(... "tool_execution_missing_result" ...)

`AgentEvent` assistant completion factory must be structured:

    public static AgentEvent assistantCompleted(String finalText,
                                                String messageId,
                                                Map<String, Object> metadata)

It must create payload:

    Map.of("messageId", messageId)

`PermissionContextFactory` method contract must be method-level, not only class-level:

    PermissionContext create(RuntimeState state);

The Javadoc must state:

    state must be non-null;
    state.conversationId must be non-blank;
    malformed security metadata such as subagent must fail closed;
    missing subagent metadata is allowed and means main runtime.

`MetadataValues` should have this internal shape:

    public static Object normalizeValue(Object value) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return normalizeValue(value, seen);
    }

    private static Object normalizeValue(Object value, Set<Object> seen)

For Map/Set/Iterable:

    if (!seen.add(value)) throw new IllegalArgumentException("metadata must not contain cycles");
    try { recurse } finally { seen.remove(value); }

`RuntimeScopeTranslator` must explicitly map:

    hooks main <-> eval MAIN
    hooks subagent <-> eval SUB_AGENT
    hooks worker <-> eval WORKER

No implementation may derive `"subagent"` by `RuntimeScope.SUB_AGENT.name().toLowerCase(...)`, because that produces `"sub_agent"`.

## Revision Notes

2026-07-10 / Codex: 新建本 ExecPlan。原因是用户提供的 `pixflow-loop` 代码审查报告指出剩余运行契约问题：executor 队列仍无界、关闭不等待；assistant 只有 tool calls 时会被投影为空文本；executor 缺失结果会破坏 tool_call/tool_result 配对；权限 subagent metadata 有 fail-open 风险；reactive compaction 防抖位在副作用成功前设置；metadata 对 NaN/Infinity、循环引用和 null 容器元素不安全；trace 名称和时间不稳定。用户同时明确“有工具调用就不停止循环”是有意设计，因此本计划决定不加 maxTurns，而是通过协议完整性、资源背压、权限 fail-closed、metadata/trace 契约收紧来完整修复。

2026-07-09 / Codex: 更新本 ExecPlan 的进度与回顾。原因是代码已按计划完成重构：assistant tool-call 一等建模、provider 投影、missing tool result 结构化错误、有界 executor 和优雅关闭、permission fail-closed、reactive compaction 防抖后置、metadata/attachment/event/error/trace 边界收紧均已落地；用户反馈已进行了全面测试且测试成功。因此将 `Progress` 六个实施阶段标记为完成，并把实际验证结果、一次上游 `pixflow-eval` 既有测试阻断记录和最终用户全面测试成功写入 `Outcomes & Retrospective`。
